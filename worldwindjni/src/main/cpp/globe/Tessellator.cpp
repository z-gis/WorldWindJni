#include "globe/Tessellator.h"

#include <algorithm>
#include <array>
#include <cmath>

namespace wwdjni {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kRad2Deg = 180.0 / kPi;
constexpr double kDeg2Rad = kPi / 180.0;
/// 墨卡托有效纬度上限（与 MercatorProjection::maxLatitude 同口径，约 85.0511°）
constexpr double kMaxLat = 85.05112877980659;

/// Web 墨卡托瓦片 (z,x,y) → 经纬度包围盒（度）。lat 用墨卡托反投影（atan·sinh）。
inline void tileToBounds(int z, int x, int y,
                         double &lonMin, double &lonMax, double &latMin, double &latMax) {
    const double n = std::pow(2.0, static_cast<double>(z));
    lonMin = x / n * 360.0 - 180.0;
    lonMax = (x + 1.0) / n * 360.0 - 180.0;
    auto rowToLat = [n](double ty) {
        const double s = kPi * (1.0 - 2.0 * ty / n);
        double lat = std::atan(std::sinh(s)) * kRad2Deg;
        if (lat > kMaxLat) lat = kMaxLat;
        if (lat < -kMaxLat) lat = -kMaxLat; // 修前误写为 `lat = kMaxLat`，导致 n=1 时 rowToLat(1) 从 −85.05
                                            // 回正 +85.05，根瓦片 latMin=latMax，AABB 塔缩到 85°N 一圈，
                                            // 被 top 平面与地平线判据双重误剔（实测 leaves=0 根因）。
        return lat;
    };
    latMax = rowToLat(static_cast<double>(y));       // 上行 → 北
    latMin = rowToLat(static_cast<double>(y) + 1.0); // 下行 → 南
}

/// 每帧构建一次的常量上下文；子递归只读，避免重复提取视锥/反算眼点。
struct SubdivCtx {
    // 相机与投影
    Vec3 eye;                                  // ECEF 米
    Vec3 eyeDir;                               // 单位向径（|E| 方向）
    std::array<FrustumPlane, 6> planes;        // Gribb 6 面（单位法向，正=内侧）
    double eyeLonDeg = 0.0, eyeLatDeg = 0.0;   // 眼点地理经纬（度），供最近点 clamp
    double eyeLen = 0.0;                       // |E|；地平线判据按瓦片局部半径 |P|²/|E| 逐瓦片计算
    // 注：不能用 R²/|E|（R=赤道半径）作固定阈值——WGS84 扁率 1/298，中纬局部地表半径
    // 比赤道半径小 ~8000m，855m 眼高下根节点 nearest.dot(ê)≈6370300 m 会小于 R²/|E|≈6385281 m
    // 而错误剔除根（实测 leaves=0）。改用 |nearest|²/|E|：北京瓦通过、反面瓦仍被剔。
    // LOD 参数
    int targetLevel = 0;
    int maxLevel = 0;
    double tanHalfFov = 0.0;
    double viewportHeight = 1.0;
    double detailFactor = 1.0;
    // 地球
    double R = 0.0;
    const Globe *globe = nullptr;
};

inline double clampd(double v, double lo, double hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

/// 瓦片矩形中「地理上最近眼点」的那点（clamp 眼点经纬到瓦片边界）→ ECEF + 距眼平方。
/// 对应 wwd AbstractTile.nearestPoint（我们只用海平面，忽略地形高程）。
inline void nearestPointOfTile(const SubdivCtx &c,
                               double lonMin, double lonMax, double latMin, double latMax,
                               Vec3 &outNearest, double &outDist2) {
    const double nLat = clampd(c.eyeLatDeg, latMin, latMax);
    const double nLon = clampd(c.eyeLonDeg, lonMin, lonMax);
    c.globe->geographicToCartesian(nLon, nLat, 0.0, outNearest);
    const Vec3 d = outNearest - c.eye;
    outDist2 = d.dot(d);
}

/**
 * 递归细分。四道闸按 wwd BasicTessellator.addTileOrDescendants 顺序：
 *   ① 地平线剔除（nearest.dot(ê) < horizonDot → 整块位于地平线下）
 *   ② 视锥 AABB 剔除（3×3 网格 ± 半球修正点，正侧极值点带符号距离 < 0 → 剪枝）
 *   ③ 屏幕空间误差 mustSubdivide（用 nearest 距，非中心距）
 *   ④ 出叶 or 4 子瓦片按 nearestDist² 升序递归（近侧先入，配额优先给最需要的瓦片）
 * 覆盖优先（对齐 wwd）：预算耗尽时不丢弃可见块，而是保留为粗级叶——见下方 outOfBudget。
 */
void subdivide(int z, int x, int y, const SubdivCtx &c, std::vector<GlobeTile> &out) {
    double lonMin, lonMax, latMin, latMax;
    tileToBounds(z, x, y, lonMin, lonMax, latMin, latMax);

    // 瓦片最近点（同时供地平线剔除与 SSE 判据）
    Vec3 nearest;
    double nearestDist2 = 0.0;
    nearestPointOfTile(c, lonMin, lonMax, latMin, latMax, nearest, nearestDist2);

    // ① 地平线剔除：nearest 是瓦片上「与眼点向径夹角最小」的地表点；
    //    以 nearest 自身长度 |P|（=瓦片处局部地球半径）为球近似，判 dot(P, ê)·|E| ≥ |P|²。
    //    等价 dot(P,ê) ≥ |P|²/|E|（避免除法，自然处理 |E| ≤ |P| 的眼在地下情况）。
    const double nearestLen2 = nearest.dot(nearest);
    if (c.eyeLen > 0.0 && nearest.dot(c.eyeDir) * c.eyeLen < nearestLen2) {
        return;
    }

    // ② 视锥测试用世界轴 AABB：3×3 地理网格 9 采样（对齐 wwd BoundingBox.setToSector 的 NUM_LAT×NUM_LON=3×3），
    //    跨经度 > 180° 时按 wwd 补 (centroid lat, lon±90°) 两点。半球瓦片的 AABB 会比真实表面略"薄"，
    //    但因为测试是保守拒绝（AABB 完全在平面外侧才剔），略保守=少剔=多留=安全；下一级细分自然修正。
    double xmin = 1e300, ymin = 1e300, zmin = 1e300;
    double xmax = -1e300, ymax = -1e300, zmax = -1e300;
    {
        const double lonArr[3] = {lonMin, 0.5 * (lonMin + lonMax), lonMax};
        const double latArr[3] = {latMin, 0.5 * (latMin + latMax), latMax};
        Vec3 p;
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) {
                c.globe->geographicToCartesian(lonArr[i], latArr[j], 0.0, p);
                if (p.x < xmin) xmin = p.x; if (p.x > xmax) xmax = p.x;
                if (p.y < ymin) ymin = p.y; if (p.y > ymax) ymax = p.y;
                if (p.z < zmin) zmin = p.z; if (p.z > zmax) zmax = p.z;
            }
        }
        if ((lonMax - lonMin) > 180.0) {
            const double latC = 0.5 * (latMin + latMax);
            const double lonC = 0.5 * (lonMin + lonMax);
            c.globe->geographicToCartesian(lonC + 90.0, latC, 0.0, p);
            if (p.x < xmin) xmin = p.x; if (p.x > xmax) xmax = p.x;
            if (p.y < ymin) ymin = p.y; if (p.y > ymax) ymax = p.y;
            if (p.z < zmin) zmin = p.z; if (p.z > zmax) zmax = p.z;
            c.globe->geographicToCartesian(lonC - 90.0, latC, 0.0, p);
            if (p.x < xmin) xmin = p.x; if (p.x > xmax) xmax = p.x;
            if (p.y < ymin) ymin = p.y; if (p.y > ymax) ymax = p.y;
            if (p.z < zmin) zmin = p.z; if (p.z > zmax) zmax = p.z;
        }
    }
    for (int pi = 0; pi < 6; ++pi) {
        const FrustumPlane &pl = c.planes[pi];
        // 平面外侧最远点：取 AABB 上让 ax+by+cz 最大的那一角（"positive vertex"）。
        const double px = (pl.a >= 0.0) ? xmax : xmin;
        const double py = (pl.b >= 0.0) ? ymax : ymin;
        const double pz = (pl.c >= 0.0) ? zmax : zmin;
        const double v = pl.a * px + pl.b * py + pl.c * pz + pl.d;
        if (v < 0.0) return;
    }

    // ③ 屏幕空间误差 mustSubdivide（wwd Tile.mustSubdivide 3D 分支去 fog 项）：
    //    texelSize = 2πR·cos(centroid lat)/2^z（赤道瓦片宽按纬度收缩；极地 clamp 0.05 防过度细分）
    //    pixelSize = 2·dNearest·tanHalfFov / viewportHeight（针孔近似，与 wwd pixelSizeAtDistance 等价）
    const double latC = 0.5 * (latMin + latMax);
    double cosLat = std::cos(latC * kDeg2Rad);
    if (cosLat < 0.05) cosLat = 0.05;
    const double texelSize = 2.0 * kPi * c.R * cosLat / std::pow(2.0, static_cast<double>(z));
    const double nearestDist = std::sqrt(nearestDist2);
    const double pixelSize = 2.0 * nearestDist * c.tanHalfFov / c.viewportHeight;
    const bool mustSubdiv = texelSize > pixelSize * c.detailFactor;

    // 覆盖优先（wwd BasicTessellator 从不为省配额丢弃可见块）：配额耗尽时不再向下细分，
    // 而是把「当前」这块作为粗级叶保留。quadtree 叶集天然构成被覆盖球面的无缝划分（不重叠、不留洞），
    // 于是远景从「缺失」降级为「模糊拉伸」，近景在耗尽前已细分到位。地平线/视锥外的块仍在①②正常剔除（不可见，不算洞）。
    const bool outOfBudget = static_cast<int>(out.size()) >= Tessellator::kMaxTiles;
    const bool leaf = outOfBudget || (!mustSubdiv) || (z >= c.targetLevel) || (z >= c.maxLevel) || (z >= 30);
    if (leaf) {
        GlobeTile t;
        t.z = z;
        t.x = x;
        t.y = y;
        t.lonMin = lonMin;
        t.lonMax = lonMax;
        t.latMin = latMin;
        t.latMax = latMax;
        out.push_back(t);
        return;
    }

    // ④ 4 子瓦片按各自 nearestDist² 升序递归——保证 DFS 优先展开眼点正下方子树，
    //    kMaxTiles 先被最需要的瓦片占据，不被 x=0 子树先入吞掉（低空瓦片风暴的 DFS 顺序根因）。
    //    注意：不再在子循环中途 return 丢弃后续子块——那会在远景留洞；每个子块自顶重新判 outOfBudget，
    //    耗尽则各自作为粗级叶返回（覆盖优先）。
    struct Child {
        int cx, cy;
        double d2;
    };
    Child ch[4];
    for (int dy = 0; dy < 2; ++dy) {
        for (int dx = 0; dx < 2; ++dx) {
            Child &cc = ch[dy * 2 + dx];
            cc.cx = 2 * x + dx;
            cc.cy = 2 * y + dy;
            double clonMin, clonMax, clatMin, clatMax;
            tileToBounds(z + 1, cc.cx, cc.cy, clonMin, clonMax, clatMin, clatMax);
            Vec3 np;
            double nd2 = 0.0;
            nearestPointOfTile(c, clonMin, clonMax, clatMin, clatMax, np, nd2);
            cc.d2 = nd2;
        }
    }
    // 4 元素选择排序，最小改动、无堆分配
    for (int i = 0; i < 3; ++i) {
        for (int j = i + 1; j < 4; ++j) {
            if (ch[j].d2 < ch[i].d2) std::swap(ch[i], ch[j]);
        }
    }
    for (int i = 0; i < 4; ++i) {
        subdivide(z + 1, ch[i].cx, ch[i].cy, c, out); // 预算耗尽时子块在入口即作粗叶返回，无需在此中途中止
    }
}

} // namespace

void Tessellator::buildVisibleTiles(const TessCamera &cam, int targetLevel, int maxLevel,
                                    double tanHalfFov, int viewportHeightPx, double detailFactor,
                                    const Globe &globe, std::vector<GlobeTile> &out) {
    out.clear();
    if (viewportHeightPx <= 0) return;
    if (targetLevel < 0) targetLevel = 0;
    if (maxLevel < 0) maxLevel = 0;

    SubdivCtx c;
    c.eye = cam.eye;
    c.planes = cam.frustumPlanes; // 直接采用 Navigator 预提的 double 平面，不再走 float viewProj
    double eyeAlt = 0.0;
    globe.cartesianToGeographic(cam.eye, c.eyeLonDeg, c.eyeLatDeg, eyeAlt);
    const double eyeLen = cam.eye.length();
    c.R = globe.equatorialRadius();
    if (eyeLen > 1e-12) c.eyeDir = cam.eye * (1.0 / eyeLen);
    c.eyeLen = eyeLen;
    c.targetLevel = targetLevel;
    c.maxLevel = maxLevel;
    c.tanHalfFov = tanHalfFov;
    c.viewportHeight = static_cast<double>(viewportHeightPx);
    c.detailFactor = detailFactor;
    c.globe = &globe;

    // Web 墨卡托根瓦片：level 0 全球 1 张 (0,0,0)
    subdivide(0, 0, 0, c, out);
}

} // namespace wwdjni
