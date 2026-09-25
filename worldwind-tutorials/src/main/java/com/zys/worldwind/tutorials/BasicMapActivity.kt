package com.zys.worldwind.tutorials

import android.os.Bundle
import com.zys.worldwindjni.Camera

/**
 * 演示 01 · 在线瓦片底图（对应 tutorials/03「最小用例」）：
 *  - [com.zys.worldwindjni.NativeMapView.addTileLayer]：磁盘缓存目录 + URL 模板 + 图源最大级别；
 *  - [com.zys.worldwindjni.NativeMapView.setCamera]：经纬度（度）+ 高度（米）定位相机；
 *  - [com.zys.worldwindjni.NativeMapView.setOnTapListener] + screenToGeo：单击屏幕点 → 经纬度；
 *  - [com.zys.worldwindjni.NativeMapView.getCamera]：读回当前相机（含 heading/tilt）。
 *
 * 交互：单指拖动平移、捏合缩放、双击放大、惯性滑行（手势已内联在 NativeMapView）。
 */
class BasicMapActivity : BaseMapActivity() {

    override val demoTitle get() = getString(R.string.demo_basic_title)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 底图 + 相机定位北京（300 万米高度约可见华北）
        addBasemap(Camera(latitude = 39.9, longitude = 116.4, altitude = 3_000_000.0))

        // 单击 → 经纬度（采集/测量加点的同款口径）
        map.setOnTapListener { x, y ->
            val pos = map.screenToGeo(x, y)
            toast(if (pos == null) "未命中地面点" else "lon=%.6f, lat=%.6f".format(pos.longitude, pos.latitude))
        }

        addDemoAction("飞行到西安") {
            map.setCamera(Camera(latitude = 34.34, longitude = 108.94, altitude = 1_000_000.0))
        }
        addDemoAction("当前相机") {
            val c = map.getCamera()
            toast(if (c == null) "native 实例已释放" else "lat=%.4f lon=%.4f alt=%.0fm".format(c.latitude, c.longitude, c.altitude))
        }
    }
}
