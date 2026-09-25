package com.zys.worldwind.tutorials

import android.os.Bundle
import com.zys.worldwindjni.Camera
import com.zys.worldwindjni.NativeMapView
import com.zys.worldwindjni.Position
import com.zys.worldwindjni.VectorStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 演示 04 · 动态叠加层绘制（对应 tutorials/03「业务叠加层」）：
 *  - [NativeMapView.addOverlayLayer]：运行时内存几何层（测量/轨迹业务同款通道）；
 *  - [NativeMapView.updateOverlayLines] / updateOverlayPolygons：覆盖式推送，单击加点即时重绘；
 *  - [NativeMapView.removeOverlayLayer]：整类拆除。
 *
 * 交互：单击地图加点 → 实时刷新折线/多边形；「撤销点」「清空」维护点集。
 */
class OverlayActivity : BaseMapActivity() {

    override val demoTitle get() = getString(R.string.demo_overlay_title)

    private val points = mutableListOf<Position>()
    private var overlayIdx = -1
    private var polygonMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addBasemap(Camera(latitude = 39.915, longitude = 116.40, altitude = 12_000.0))

        // 叠加层「加一次、就地更新多次」：橙红线 + 半透明填充（多边形模式生效）
        overlayIdx = map.addOverlayLayer(
            VectorStyle(
                fillColor = 0x4DE56B33,
                lineColor = 0xFFE56B33.toInt(),
                lineWidth = 3f,
                pointColor = 0xFFE56B33.toInt(),
                pointRadiusDp = 4f,
            )
        )

        refresh()
        map.setOnTapListener { x, y ->
            map.screenToGeo(x, y)?.let {
                points.add(it)
                refresh()
            }
        }

        addDemoAction("切换：多边形") { toggleMode() }
        addDemoAction(getString(R.string.action_undo)) {
            if (points.isNotEmpty()) {
                points.removeAt(points.lastIndex)
                refresh()
            }
        }
        addDemoAction(getString(R.string.action_clear)) {
            points.clear()
            refresh()
        }
        addDemoAction("示例圆周") { loadCircleSample() }
    }

    private fun toggleMode() {
        polygonMode = !polygonMode
        toast(if (polygonMode) "当前：多边形（首尾自动闭合成面）" else "当前：折线")
        // 按钮文案无法就地改（demo 简化），以 toast 提示当前模式
        refresh()
    }

    /** 覆盖式推送当前点集：折线单段 / 多边形闭合单环 */
    private fun refresh() {
        val lonlat = DoubleArray(points.size * 2).also { arr ->
            points.forEachIndexed { i, p ->
                arr[i * 2] = p.longitude
                arr[i * 2 + 1] = p.latitude
            }
        }
        // 先清空两类几何再按当前模式推送，避免线/面切换时残留旧几何
        map.updateOverlayLines(overlayIdx, DoubleArray(0), IntArray(0))
        map.updateOverlayPolygons(overlayIdx, DoubleArray(0), IntArray(0), IntArray(0))
        when {
            polygonMode && points.size >= 3 -> {
                // 闭合环：末尾补首个点
                val closed = lonlat + doubleArrayOf(lonlat[0], lonlat[1])
                map.updateOverlayPolygons(
                    overlayIdx, closed,
                    ringVertexCounts = intArrayOf(points.size + 1),
                    ringsPerFeature = intArrayOf(1),
                    labels = arrayOf("${points.size} 顶点面"),
                )
            }
            !polygonMode && points.size >= 2 -> {
                map.updateOverlayLines(
                    overlayIdx, lonlat,
                    vertexCounts = intArrayOf(points.size),
                    labels = arrayOf("${points.size} 顶点线"),
                )
            }
        }
    }

    /** 一键生成示例：以相机为中心的 32 边形（演示批量几何推送） */
    private fun loadCircleSample() {
        val c = map.getCamera() ?: return
        points.clear()
        val rDeg = 0.03
        for (i in 0 until 32) {
            val a = i * 2 * PI / 32
            points.add(
                Position(
                    latitude = c.latitude + rDeg * sin(a),
                    longitude = c.longitude + rDeg * cos(a) / cos(PI / 180 * c.latitude),
                )
            )
        }
        polygonMode = true
        refresh()
        toast("已生成 32 顶点示例多边形")
    }
}
