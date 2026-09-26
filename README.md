# WorldWindJni

把 WorldWind 渲染内核下沉为 **一套 C++ 共享渲染引擎，双平台产物**：Android 侧 Kotlin + **JNI/AAR**，
HarmonyOS NEXT 侧 ArkTS + **NAPI/HAR**（1.1.0 新增，工程见 `worldwind-ohos/`，鸿蒙集成细节见文末同名章节）。
门面层（`NativeMapView` 等）只做接口转发，相机、瓦片网格、取瓦片与缓存、矢量读取/三角剖分、
OpenGL ES 2.0 绘制全部在 native 完成，并内联编译了 GDAL/OGR、PROJ、libcurl 全套第三方静态库能力。

- **1.1.0**：新增 HarmonyOS NEXT（API 12 / 5.0.0）支持；两端共用引擎源（鸿蒙经 `WW_ENGINE_ROOT`
  直接引用，不复制不搬迁）；Android 侧仅日志分派适配，API 与产物兼容 1.0.0
- 支持 2D 平面墨卡托 / 3D WGS84 球体双视图模式
- 瓦片图源（在线 URL 模板 + 磁盘缓存互通）、本地栅格（tif/img GDAL 重投影切片）
- 矢量（shp/kml/kmz/dwg/dxf 等 OGR 可打开格式）直读、重投影、earcut 三角剖分、要素拾取
- 动态叠加层（测量/轨迹/拍照标识等业务几何）、矢量标注（字形图集）、定位标记
- PROJ 数据（proj.db 与格网改正数）随模块分发（Android assets / 鸿蒙 rawfile），宿主一行初始化

## 仓库结构

```
WorldWindJni/
├── worldwindjni/                    # Android Library 模块（Kotlin 门面 + cpp 渲染内核；引擎源在此）
│   └── src/main/cpp/
│       ├── jniLibs/<abi>/           # 第三方预编译 .a（编译产物，不入仓，见「0 → 1 从零构建」）
│       └── include/                 # 三方头（gdal/proj/curl/openssl/sqlite/zlib 由构建生成，不入仓）
│                                    #   stb/ 与 earcut.cpp 为仓库自带 vendored 源，随仓提供
├── worldwind-tutorials/             # 功能演示 App（MainActivity 菜单逐项体验，project 依赖库模块）
├── worldwind-ohos/                  # HarmonyOS NEXT 工程（DevEco Studio 打开；library HAR + entry 演示 HAP）
│   └── library/src/main/cpp/        #   鸿蒙专属源（EGL/XComponent/NAPI）+ 经 WW_ENGINE_ROOT 引用引擎源
├── build-scripts/                   # 第三方静态库编译脚本（WSL/Linux；--target android|ohos 分派 NDK）
└── gradle/ + 根构建脚本             # 独立 Gradle 工程（Gradle 9.4.1 / AGP 9.2.1）
```

## 0 → 1 从零构建

新克隆本仓库**不含**下列编译产物，需要先运行 `build-scripts` 一键生成，之后 Gradle / Android
Studio 才能编译通过：

| 产物 | 位置 | 是否入仓 | 生成方式 |
|---|---|---|---|
| 第三方 `.a`（每 ABI 14 个） | `worldwindjni/src/main/cpp/jniLibs/{x86_64,arm64-v8a}/` | ❌ | `build-all.sh` |
| 三方头文件 | `worldwindjni/src/main/cpp/include/{gdal,proj,curl,openssl,sqlite}`、`zlib.h`/`zconf.h` | ❌ | `build-all.sh`（install_headers，双平台共用） |
| PROJ 运行期数据 | `worldwindjni/src/main/assets/proj/`（proj.db 等） | ❌ | `build-all.sh`（install_proj_data） |
| 鸿蒙第三方 `.a`（每 ABI 14 个） | `build-scripts/out/ohos/{arm64-v8a,x86_64}/` | ❌ | `build-all.sh --target ohos` |
| 鸿蒙 PROJ 运行期数据 | `worldwind-ohos/library/src/main/resources/rawfile/proj/` | ❌ | 同上（install_proj_data 按平台落位） |

native 构建需要每 ABI 14 个 `.a`（约 450MB/ABI，其中 libgdal.a 约 330MB），体积过大不随仓库分发：

```
libgdal.a  libproj.a  libsqlite3.a  libkmlbase.a  libkmldom.a  libkmlengine.a
libkmlxsd.a  libkmlconvenience.a  libkmlregionator.a  liburiparser.a
libminizip.a  libexpat.a  libz.a  libcurl.a
```

> ⚠️ `jniLibs/` 的 ABI 目录名必须与 `ANDROID_ABI` 完全一致。
> `libcurl.a` 内部已静态打包 OpenSSL 1.1.1w + zlib，无需再单独链
> libssl/libcrypto。

### 路径 A：下载现成产物（免编译，推荐快速上手）

本仓库 [Releases](https://github.com/z-gis/WorldWindJni/releases) 提供与头文件版本配套的预编译
产物包 **`worldwindjni-prebuilt-libs-v1.0.0.zip`**（约 106MB，含双 ABI 共 28 个 `.a` + 三方头 +
`assets/proj` 数据，共 290 个文件）。压缩包保持仓库相对路径（顶层 `worldwindjni/...`），在**仓库根
目录**解压即全部落位，无需本地编译工具链：

```bash
# 于仓库根目录执行
curl -LO https://github.com/z-gis/WorldWindJni/releases/download/v1.0.0/worldwindjni-prebuilt-libs-v1.0.0.zip
unzip worldwindjni-prebuilt-libs-v1.0.0.zip   # 产物落入 cpp/jniLibs、cpp/include、assets/proj
```

> 直链：<https://github.com/z-gis/WorldWindJni/releases/download/v1.0.0/worldwindjni-prebuilt-libs-v1.0.0.zip>
> 该包由路径 B 的 `build-all.sh` 产出后打包，若你更新了三方库版本，请用路径 B 重编并同步更新 Release。
> 1.1.0 未变更任何三方库版本，该包对 Android 1.1.0 依然有效；鸿蒙 `.a` 需另跑路径 B 的 `--target ohos`。

### 路径 B：用 build-scripts 一键自行编译（推荐）

脚本位于 **`build-scripts/`**，在 **WSL2 / Linux** 下运行（Windows 直接跑不通）。一次执行即
**同时产出 x86_64 与 arm64-v8a 两套共 28 个 `.a`**，并自动刷新 `include/` 三方头与
`assets/proj/` 数据，全部落回仓库对应目录。

依赖（脚本会自动探测 / 下载，一般无需手动准备）：
- Android NDK **r23b**（编译期工具链；与模块自身 Gradle 构建用的 NDK 28.x 不冲突，静态库 ABI 兼容）
- cmake ≥ 3.22（Linux 版）
- 网络可达 github / osgeo / sqlite 等源码站（首次会下载各 tarball）

只需一条命令：

```bash
cd build-scripts
bash build-all.sh
```

要点：
- 无需手改 ABI / MAIN_DIR。脚本内置双 ABI 循环，按依赖顺序构建
  （expat → minizip/zlib → uriparser → openssl → curl → boost 头 → libkml → sqlite3 → proj → gdal）。
- **WSL 下若脚本在 Windows 挂载盘（`/mnt/<盘>`，9p/drvfs）**：NDK/cmake 解压出的符号链接与可执行位
  无法保留，脚本会自动把构建工作区重定向到原生目录 `~/android-jni-build`（ext4），产物仍按仓库路径
  落回挂载盘上的 `jniLibs/`、`include/`、`assets/proj/`。
- 可离线复用：把已下载的 `*.tar.gz`/`*.zip` 与 `boost/` 头放进 `build-scripts/third-party/`
  （`PKG_CACHE`），脚本会 `cp -n` 种入工作区，避免重复下载。
- **鸿蒙产物**：把 `--target ohos` 追加到任一命令即可（`bash build-all.sh --target ohos`），
  ABI 换为 `arm64-v8a`（真机）+ `x86_64`（DevEco 模拟器），工具链改用 OHOS NDK。
  华为侧 NDK 需登录账号下载，脚本只探测不自动下载：解压 Command Line Tools SDK 里的
  `.../sdk/default/openharmony/native` 后 `export OHOS_NDK_ROOT=<路径>/openharmony/native`
  （或放入 `build-scripts/ndk/` 下），探测口径 `llvm/bin/clang` + `build/cmake/ohos.toolchain.cmake`。
  产物落 `build-scripts/out/ohos/<abi>/`，三方头与 `assets/proj` ↔ `rawfile/proj` 按平台自动分流，
  与 Android 产物目录完全隔离、可重复交叉构建。

各源码包版本（须与生成头文件配套）：

| 源码包 | 版本 | 产物 |
|---|---|---|
| gdal | 3.7.0 | libgdal.a |
| proj | 9.0.0 | libproj.a + assets/proj 数据 |
| sqlite-autoconf | 3370200 | libsqlite3.a |
| libkml | 1.3.0 | libkml{base,dom,engine,xsd,convenience,regionator}.a |
| expat | 2.4.8 | libexpat.a |
| zlib | 1.2.13 | libz.a + libminizip.a |
| uriparser | 0.9.8 | liburiparser.a |
| openssl | 1.1.1w | 并入 libcurl.a |
| curl | 8.4.0 | libcurl.a |
| boost | （仅头文件） | libkml 编译依赖 |

## 构建 AAR

1. 完成上面「0 → 1」拿到 `.a` + 头 + PROJ 数据。
2. 构建：

   ```bat
   gradlew.bat :worldwindjni:assembleRelease
   ```

   产物：`worldwindjni/build/outputs/aar/worldwindjni-release.aar`。
   首次构建会由 CMake 全量编译 native 内核并静态链接 GDAL 链。

构建环境：JDK 21+（wrapper 自动解析）、Android SDK（compileSdk 37）、NDK `28.2.13676358`、
CMake `3.22.1`。验证链接是否缺库：报 `cannot find -lxxx` / `Unable to find library` 即 `.a`
缺文件或目录名不对。

## 引用方式：Maven 坐标（推荐，Gradle/IDE 自动下载）

发布产物托管在本仓库的 **GitHub Pages Maven 仓**，宿主**无需手动下载 AAR**——在 `settings.gradle.kts`
的 `dependencyResolutionManagement.repositories` 里加一行仓库，再按坐标引用：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://z-gis.github.io/WorldWindJni/maven") } // worldwindjni
    }
}
```

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        minSdk = 24
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }
}
dependencies {
    implementation("com.zys:worldwindjni:1.1.0")   // 自动下载；传递依赖（androidx core-ktx）随之带入
}
```

> 注意：仓库地址要加在 **`dependencyResolutionManagement.repositories`**（解析 `implementation` 依赖用）；
> 只加进 `pluginManagement.repositories` 不对——那处只管 Gradle 插件，坐标会报
> `Could not find com.zys:worldwindjni:x.y.z`。本仓库的 `worldwind-tutorials` 演示模块同样按此配置，
> 把 `implementation(project(":worldwindjni"))` 换成上面的 Maven 坐标即可验证“外部消费者”体验。
>
> 与下方手动 files() 方式的区别：Maven 坐标会自动解析传递依赖（无需再手写 `core-ktx`）。
> 其余（`NativeSrs.initProjData` 初始化、宿主自行声明 `INTERNET` 权限、`NativeMapView` 用法）与
> 「AAR 三步曲」的第二~四步完全一致。
>
> 发布由 `.github/workflows/publish-maven.yml` 完成（推 `v*` tag 或手动 dispatch：下载 Release 里的
> `.a` 预置包 → 编 AAR → 部署到 `gh-pages:/maven`）。

## 备选：手动引入 AAR 文件（无网络仓库时的“三步曲”）

worldwindjni 以 **AAR 文件**方式集成，无需引入源码模块。

**第一步：放入 AAR** —— 把 `worldwindjni-release.aar` 复制到宿主工程 `app/libs/`（宿主无需安装
NDK/CMake，渲染内核已编译进 AAR 的 `jni/*.so`）。

**第二步：声明依赖** —— `app/build.gradle.kts`：

```kotlin
dependencies {
    // files() 方式不解析传递依赖，宿主需自带 androidx core-ktx
    implementation(files("libs/worldwindjni-release.aar"))
    implementation("androidx.core:core-ktx:1.10.1")
}

android {
    defaultConfig {
        minSdk = 24
        ndk {
            // AAR 仅打包这两个 ABI，宿主保持一致避免运行期找不到 .so
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }
}
```

AAR 内置的 `proguard.txt`（consumer rules）会自动生效，宿主开混淆也无需额外配置。

**第三步：启动初始化** —— PROJ 数据（proj.db 与格网改正数文件）随 AAR assets 分发、合入宿主
APK。宿主启动时（首次坐标转换 / 建图层之前）调用一次：

```kotlin
// 解压 assets/proj 到 filesDir/proj 并设置 PROJ 搜索路径；已存在的文件跳过，幂等可重复调
com.zys.worldwindjni.NativeSrs.initProjData(context)
```

未初始化时坐标转换按既有口径返回 `null`（不崩溃），调用方需自行回退。验证集成：

```kotlin
NativeSrs.convert(116.4, 39.9, "EPSG:4326", "EPSG:3857")  // 非 null 即成功
```

集成常见问题：
- **`UnsatisfiedLinkError: libworldwindjni.so`**：宿主 abiFilters 未包含目标 ABI，或 APK 里同名
  .so 被其它依赖覆盖。
- **坐标转换全部返回 null**：漏了第三步 `initProjData`。
- **升级 AAR 后 assets 旧数据残留**：解压逻辑按「文件已存在即跳过」，升级 PROJ 数据需先清应用数据
  （或自行删除 `filesDir/proj`）。

## API 概览与快速上手

公开 API 均为 Kotlin 门面类（`com.zys.worldwindjni` 包），JNI 细节封装在 internal 的
`NativeLib` 中，宿主不需要直接接触。各方法语义以源码 KDoc 为准，本节只给地图接入最小路径。

门面类一览：

| 类 | 职责 |
|---|---|
| `NativeMapView` | 地图视图（继承 GLSurfaceView）：相机、视图模式、瓦片/栅格/矢量/叠加层、拾取、定位标记 |
| `NativeSrs` | PROJ 初始化（`initProjData`）与任意坐标系互转（`convert`）、PROJ 版本 |
| `NativeLayerInfo` | 矢量/栅格文件元信息：四至、坐标系、字段名（建图层前探测用） |
| `NativeVector` | 矢量读写与 SQL 查询桥接（GDAL/OGR 能力入口） |
| `Camera` / `Position` / `VectorExtent` / `VectorStyle` / `FeatureGeometry` | 值对象 |
| `MapGestures` | 手势识别（已内联进 `NativeMapView`，自定义视图时复用） |

最小用例：显示在线瓦片底图：

```kotlin
class MapActivity : AppCompatActivity() {

    private lateinit var map: NativeMapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 一次性：解压 AAR 内置 proj 数据并配置 PROJ 搜索路径（见上文第三步）
        NativeSrs.initProjData(this)

        map = NativeMapView(this)
        setContentView(map)

        // 瓦片层：磁盘缓存目录 + URL 模板（{z}/{x}/{y} 占位）+ 图源最大级别
        map.addTileLayer(
            cacheDir = File(filesDir, "tiles/img").absolutePath,
            urlTemplate = "https://.../wmts?...&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}",
            maxLevel = 18,
        )
        // 相机：经纬度（度）+ 高度（米）
        map.setCamera(Camera(latitude = 39.9, longitude = 116.4, altitude = 3_000_000.0))
    }

    // GLSurfaceView 生命周期要求
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onDestroy() { map.destroy(); super.onDestroy() }  // 释放 native 实例
}
```

3D 球体：

```kotlin
map.setViewMode(NativeMapView.ViewMode.THREE_D)  // 与 2D 共用相机状态，切换保持视角连续
```

矢量图层与拾取：

```kotlin
// OGR 可打开的任意格式；样式颜色为 #AARRGGBB 打包 Int；异步读取+三角剖分，就绪自动重绘
// （后续参数：图标像素/尺寸、加载范围、要素上限，均有默认值）
val idx = map.addVectorLayer(path, style /* VectorStyle */)

map.setOnTapListener { x, y ->
    val hit = map.pickVector(x, y)                       // [layerIndex, fid]，未命中 null
    if (hit != null) { val geo = map.featureGeometry(hit[0].toInt(), hit[1]) }
    val pos = map.screenToGeo(x, y)                       // 单击转经纬度（采集/测量加点）
}
```

大数据集用 `updateVectorExtent(index, extent, maxFeatures)` 按屏幕范围增量重载（Swap-on-ready
无空窗），`hasVectorLoading()` 轮询驱动「加载中」提示。

业务叠加层（测量/轨迹/拍照标识）：`addOverlayLayer` 建运行时内存几何层，经
`updateOverlayPoints/Lines/Polygons` 覆盖式推送，`removeOverlayLayer` 移除；瞬态高亮层可
`setOverlayNoPick` 退出拾取竞争。

线程约定：
- 所有 `NativeMapView` 公开方法只做 native 状态更新 + `requestRender`，不涉及 GL 调用，可在任意
  线程调用（含 Surface 创建前）；
- 矢量读取、瓦片下载在 native 后台线程池完成，就绪后回调 `requestRender` 异步上屏。

## 功能演示 App（worldwind-tutorials 模块）

仓库内置一个类 NASA worldwind-tutorials 的演示应用，逐项展示库能力：

1. 在线瓦片底图 + 相机 + 单击取点
2. 2D / 3D 视图切换与倾斜视角
3. 矢量图层（内置 GeoJSON）+ 要素拾取高亮
4. 动态叠加层：单击画折线/多边形（测量口径）
5. 定位标记蓝点 + 方向箭头（模拟 1Hz 回调）
6. PROJ 坐标转换（EPSG:4326 ↔ EPSG:3857）

运行：先完成「0 → 1」拿到 `.a`/头/PROJ 数据，Android Studio 打开本仓库并 **Sync**，选择
`worldwind-tutorials` 配置直接装机；或

```bat
gradlew.bat :worldwind-tutorials:assembleDebug
```

底图默认用 OSM 公共瓦片（免 token，需联网），可在 `TileSources.kt` 换成自有图源。

## 鸿蒙 HarmonyOS NEXT 集成（1.1.0 新增）

### 架构与对应关系

HarmonyOS NEXT（API 12 / 5.0.0，纯血鸿蒙，无 AOSP 兼容层）下引擎同一套 C++ 源直接参与编译，
平台差异收敛为三类适配点：

| 职责 | Android | HarmonyOS NEXT |
|---|---|---|
| 引擎源 | `worldwindjni/src/main/cpp/*` | 经 CMake 变量 `WW_ENGINE_ROOT` 直接引用同一目录，不复制不搬迁 |
| 日志 | `android/log` | hilog（`Log.h` 按 `__OHOS__` 分派，引擎其余代码零平台 `#ifdef`） |
| GL 宿主 | Kotlin `GLSurfaceView` 转发 surface 生命周期 | `XComponent(SURFACE)` + native EGL 渲染线程（`ohos/EglContext`、`ohos/XComponentBridge`），渲染后端仍为 GLES（向下兼容 ES 2.0 绘制指令，`render/` 层零重写） |
| 桥接 | JNI（`WorldWindowJni.cpp`） | NAPI（`ohos/napi_*.cpp`，导出清单与 JNI 一一对应） |
| 门面 | Kotlin `com.zys.worldwindjni` | ArkTS HAR `worldwind`（`worldwind-ohos/library`，类名/方法名/参数序完整对齐） |
| 资源 | AAR `assets/proj` | HAR `resources/rawfile/proj`（均经 `NativeSrs.initProjData` 解压到沙箱 `filesDir/proj`） |
| 产物 | AAR（`com.zys:worldwindjni`） | HAR（`worldwind`，so 名 `libworldwind.so`） |

### 集成三步曲（对齐 Android 侧口径）

**第一步：引入 HAR** —— 把 `worldwind.har`（DevEco `Build Module → Make Module HAR` 产出）放入宿主，
`oh-package.json5` 声明依赖（源码仓库直接引用时也可 `file:` 指向 `library` 模块）：

```json5
// oh-package.json5
{
  "dependencies": {
    "worldwind": "file:./libs/worldwind.har"
  }
}
```

**第二步：声明权限** —— 取瓦片走 native libcurl，宿主 `module.json5` 自行声明（HAR 不携权限）：

```json5
// module.json5（module 节内）
{
  "requestPermissions": [{ "name": "ohos.permission.INTERNET" }]
}
```

**第三步：启动初始化** —— Ability `onCreate` 里调一次（幂等；首次坐标转换/栅格重投影之前）：

```typescript
import { NativeSrs } from 'worldwind';

// 解压 HAR rawfile/proj 到 filesDir/proj 并配置 PROJ 搜索路径（this.context 为 UIAbilityContext）
NativeSrs.initProjData(this.context);
```

未初始化时坐标转换按既有口径返回 `null`（不崩溃）；验证集成：

```typescript
NativeSrs.convert(116.4, 39.9, 'EPSG:4326', 'EPSG:3857')  // 非 null 即成功
```

### 地图接入最小路径

`NativeMapView` 是 `@Component` struct（**不可 `new`**）：声明在 `build()` 里，组件引用经 `onReady`
回调挂载后再驱动 API（相机/图层可在 XComponent 就绪前设置，native 句柄在组件
`aboutToAppear` 已创建）：

```typescript
import { NativeMapView, Camera, ViewMode } from 'worldwind';

@Entry
@Component
struct MapPage {
  private mapView: NativeMapView | undefined = undefined;

  private initMap(view: NativeMapView): void {
    this.mapView = view;
    view.addTileLayer(`${getContext(this).filesDir}/tiles/osm`,
      'https://tile.openstreetmap.org/{z}/{x}/{y}.png', 19);
    view.setCamera(new Camera(39.9, 116.4, 3000000.0));
    view.setViewMode(ViewMode.THREE_D);
  }

  build() {
    NativeMapView({
      density: 1.0,
      onReady: (v: NativeMapView): void => {
        this.initMap(v);
      },
    })
      .width('100%').height('100%')
  }
}
```

其余 API（矢量/栅格/叠加层/拾取/定位标记/手势）与 Kotlin 侧逐一对应，参「API 概览与快速上手」；
生命周期无需手动 onPause/onResume：native EGL 线程随 XComponent 加载/卸载自动 attach/detach。

### 演示工程 worldwind-ohos

DevEco Studio（5.0+，API 12 SDK）打开 `worldwind-ohos/`，`ohpm install` 后直接 run `entry`：
清单页 `Index` 六项（底图 / 3D / 矢量 / 叠加层 / 定位标记 / 坐标转换）路由到综合演示页
`MapDemoPage`，能力口径对齐 `worldwind-tutorials`。矢量/栅格样例文件经 hdc 推送到应用沙箱
`filesDir` 即自动被演示页发现：

```bat
hdc file send D:\data\sample.shp /data/app/el2/100/base/com.zys.worldwind.ohos/files/sample.shp
```

（需开发者模式 + root / `hdc shell mount -o remount,rw`；无样例文件时底图功能不受影响，仅 toast 提示。）

编译前提：先完成「0 → 1」鸿蒙分支（`build-all.sh --target ohos` 产出 `build-scripts/out/ohos`），
否则 CMake 报找不到 `.a`。常见问题：
- **`cannot find -lxxx` / IMPORTED 库不存在**：鸿蒙 `.a` 未产出，或 ABI 目录名与 `OHOS_ARCH` 不一致。
- **`dlopen failed: libworldwind.so`**：HAR 打包未含目标 ABI 的 so；真机 `arm64-v8a`，模拟器 `x86_64`。
- **坐标转换全部返回 null**：漏了第三步 `initProjData`；升级 PROJ 数据需先清应用数据（解压按
  「文件已存在即跳过」）。
- **日志查看**：`hdc shell hilog | findstr WorldWind`（引擎 `Log.h` 鸿蒙分支统一走 hilog）。

## 版本说明

- **1.1.0（当前）**：新增 HarmonyOS NEXT 双平台支持（`worldwind-ohos` 工程 + `build-scripts --target ohos`）；
  Android 侧仅 `Log.h` 平台分派适配，API/ABI/产物与 1.0.0 完全兼容；三方库版本未变更。
  GitHub Release tag `v1.1.0` 与 Maven `com.zys:worldwindjni:1.1.0` 发布随本次改造一并打点（发布流程
  见 `.github/workflows/publish-maven.yml`）。
- **1.0.0**：Android 单平台首个正式版（AAR + Maven 仓 + prebuilt-libs 产物包）。

## 许可与第三方声明

本仓库源码按 MobileMap 项目许可发布。产物 AAR 静态链接以下第三方库，对外分发时须保留其版权声明：

| 库 | 版本 | 许可证 |
|---|---|---|
| GDAL | 3.7.0 | MIT |
| PROJ | 9.0.0 | MIT |
| libkml | 1.3.0 | BSD-3 |
| SQLite | 3.37.2 | public domain |
| expat | 2.4.8 | MIT |
| zlib | 1.2.13 | zlib |
| uriparser | 0.9.8 | MIT |
| minizip | (zlib 附带) | zlib |
| OpenSSL | 1.1.1w | Apache-style 2.0（并入 libcurl.a） |
| libcurl | 8.4.0 | curl |
| boost（仅头文件，libkml 依赖） | — | BSL-1.0 |
