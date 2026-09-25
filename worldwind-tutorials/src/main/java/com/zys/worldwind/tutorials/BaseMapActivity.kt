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

    /** 挂在线瓦片底图（OSM 公共图源）并把相机定位到 [camera]（默认北京） */
    protected fun addBasemap(camera: Camera = Camera(39.9, 116.4, 3_000_000.0)) {
        map.addTileLayer(
            cacheDir = File(filesDir, "tiles/osm").absolutePath,
            urlTemplate = TileSources.OSM,
            maxLevel = TileSources.OSM_MAX_LEVEL,
        )
        map.setCamera(camera)
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
