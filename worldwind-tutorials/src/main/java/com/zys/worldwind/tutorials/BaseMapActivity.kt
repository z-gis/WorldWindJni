package com.zys.worldwind.tutorials

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zys.worldwindjni.Camera
import com.zys.worldwindjni.NativeMapView
import com.zys.worldwindjni.NativeSrs
import java.io.File

/**
 * 演示页通用骨架：PROJ 初始化、[NativeMapView] 创建与 GL 生命周期转发、
 * 顶部「标题 + 横向动作按钮条」布局，让各演示子类只聚焦自己的功能 API。
 *
 * 集成三步曲（tutorials/01）在这里一次完成：
 *  [NativeSrs.initProjData] → 创建 [NativeMapView] → Activity 生命周期转发
 *  （onResume/onPause 驱动 GL 线程，onDestroy 释放 native 实例）。
 */
abstract class BaseMapActivity : AppCompatActivity() {

    /** 地图视图（native 渲染内核的 Kotlin 门面，等价 wwd 的 WorldWindow） */
    protected lateinit var map: NativeMapView
        private set

    private lateinit var actionRow: LinearLayout

    /** 页面标题（演示项名称），显示在顶栏 */
    protected abstract val demoTitle: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 一次性：解压内置 proj 数据并设置 PROJ 搜索路径（幂等，可重复调）
        NativeSrs.initProjData(this)

        map = NativeMapView(this)

        val titleView = TextView(this).apply {
            text = demoTitle
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(12), dp(28), dp(12), dp(4))
            addView(titleView)
            addView(HorizontalScrollView(this@BaseMapActivity).apply { addView(actionRow) })
        }

        setContentView(FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        })
    }

    /** 在动作条追加一个演示按钮 */
    protected fun addDemoAction(label: String, onClick: () -> Unit) {
        actionRow.addView(Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setOnClickListener { onClick() }
        })
    }

    /**
     * 挂内置 worldtopobathy 世界图作离线基图（仿 wwd BackgroundLayer——tutorials/03「基础瓦片」的离线等价）：
     * 因当前预编译 libgdal 裁剪了 PNG 驱动（无 GDAL 栅格即时重投影通路），改走已工作的瓦片磁盘缓存管线：
     * 把 assets/tiles/topo 预切的全球墨卡托 XYZ 瓦片（布局 `<z>/<x>_<y>.tile`）解到文件目录，以空 URL 模板
     * 的 [NativeMapView.addTileLayer]（只读盘、不联网）作底图——零网络、永不棋盘。
     * [onlineDetail]=true 时另挂一个 OSM 在线详细层（底图同趟、绘制在 topo 之上矢量之下），
     * 默认隐藏并加「在线详细 开/关」按钮：常规只看离线世界图（不棋盘），联网后手动开启取街景细节。
     * 相机定位到 [camera]。
     */
    protected fun addTopoBasemap(
        camera: Camera = Camera(20.0, 0.0, 30_000_000.0),
        onlineDetail: Boolean = false,
    ) {
        val topoCache = File(filesDir, "tiles/topo")
        // 幂等解压：用版本标记文件控制，仅首次（或瓦片版本升级）时拷 85 个瓦片
        val marker = File(topoCache, ".ver")
        val ver = TileSources.TOPO_MAX_LEVEL.toString()
        if (runCatching { marker.readText() }.getOrNull() != ver) {
            copyAssetsDir(TileSources.TOPO_ASSET_DIR, topoCache)
            marker.writeText(ver)
        }
        map.addTileLayer(
            cacheDir = topoCache.absolutePath,
            urlTemplate = "",  // 空模板禁用联网：全部瓦片已在磁盘缓存
            maxLevel = TileSources.TOPO_MAX_LEVEL,
        )
        if (onlineDetail) {
            // 图层按加入次序共享下标（layers_）：BaseMapActivity 中本方法恒为最先建层，故 topo=0、OSM=1
            val osmIdx = 1
            map.addTileLayer(
                cacheDir = File(filesDir, "tiles/osm").absolutePath,
                urlTemplate = TileSources.OSM,
                maxLevel = TileSources.OSM_MAX_LEVEL,
            )
            map.setLayerVisible(osmIdx, false)  // 默认隐藏：常规只看离线世界图，避免断网时详细层显棋盘
            addDemoAction("在线详细 开") { map.setLayerVisible(osmIdx, true) }
            addDemoAction("在线详细 关") { map.setLayerVisible(osmIdx, false) }
        }
        map.setCamera(camera)
    }

    /** 递归把 assets 下目录（含子目录）解到目标文件目录（非空子项视为目录递归，否则当文件拷贝）。 */
    private fun copyAssetsDir(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val children = assets.list(assetPath) ?: return
        for (name in children) {
            if (name.isEmpty()) continue
            val childAsset = "$assetPath/$name"
            val sub = assets.list(childAsset)
            if (sub != null && sub.isNotEmpty()) {
                copyAssetsDir(childAsset, File(destDir, name))
            } else {
                val dst = File(destDir, name)
                assets.open(childAsset).use { src -> dst.outputStream().use { out -> src.copyTo(out) } }
            }
        }
    }

    protected fun toast(msg: CharSequence) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    protected fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    // GLSurfaceView 生命周期要求：转发驱动 GL 线程与 native 实例释放
    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        map.destroy()  // 释放 native WorldWindow 实例
        super.onDestroy()
    }
}
