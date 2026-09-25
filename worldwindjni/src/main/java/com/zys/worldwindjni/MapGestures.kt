package com.zys.worldwindjni

import android.content.Context
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.OverScroller

/**
 * 手势识别器的动作接收端：[MapGestures] 把识别出的平移/缩放动作交给本接口，
 * 由 [NativeMapView] 实现（转调 native 相机操作并请求重绘）。
 */
internal interface GestureSink {
    /** native 实例是否可用（句柄已释放时手势不再驱动 native，对齐原 h != 0L 逐项守卫） */
    fun isReady(): Boolean

    /** 平移 [dxPx]/[dyPx] 像素（符号已在 [MapGestures] 侧折算为内容移动方向） */
    fun panBy(dxPx: Double, dyPx: Double)

    /** 以 ([focusX], [focusY]) 为锚缩放：[scaleFactor] > 1 为放大 */
    fun zoomBy(scaleFactor: Double, focusX: Float, focusY: Float)

    /** 双指旋转：相机 heading 累加增量（度）；「内容跟随手指」的方向折算已在本识别器完成（真机标定），
     * 实现方直接透传 native */
    fun rotateBy(deltaDeg: Double)

    /** 当前是否 3D 透视模式：双指纵向俯仰（tilt）仅在 3D 生效，且 3D 下双指拖动让位 tilt 不再平移 */
    fun is3DMode(): Boolean

    /** 双指纵向拖拽：相机 tilt 累加增量（度，正=向地平线方向倾视）；「上推倾视/下拉回正」的
     * 方向折算与灵敏度换算已在本识别器完成，实现方仅做 3D 门控后透传 native（native 钳 [0,75]） */
    fun tiltBy(deltaDeg: Double)

    /** 请求重绘一帧（对齐 GLSurfaceView.requestRender） */
    fun requestRender()
}

/**
 * 地图手势识别器（自 [NativeMapView] 拆出，行为逐项等价）：
 * 拖动平移、双指捏合缩放、双指旋转（3D 方位角）、双指纵向俯仰（3D 仰角 tilt）、双击放大、
 * 单击透出、松手惯性滑行（OverScroller 减速 + Choreographer 逐帧把位移增量交给 [GestureSink.panBy]，
 * 对齐 wwd 滑动阻尼）。
 *
 * 只负责「识别 + 调度」，不持有 native 句柄：全部动作经 [sink] 回调落地，
 * 句柄守卫、GL 请求由实现方（[NativeMapView]）承担。
 */
internal class MapGestures(
    context: Context,
    private val sink: GestureSink
) {

    /** 单击（非双击/拖拽）回调：透出视图内屏幕像素坐标，供宿主做矢量要素拾取。 */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    /** 捏合缩放：以焦点为锚调用缩放（scaleFactor>1 即两指张开 → 放大/拉近） */
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (sink.isReady()) {
                    sink.zoomBy(detector.scaleFactor.toDouble(), detector.focusX, detector.focusY)
                    sink.requestRender()
                }
                return true
            }
        }
    )

    /** 平移惯性滑动：松手后按 fling 初速度减速滑行（对齐 wwd 滑动阻尼） */
    private val scroller = OverScroller(context)
    private val choreographer = Choreographer.getInstance()
    private var lastFlingX = 0
    private var lastFlingY = 0
    private var flingRunning = false

    /** 双指旋转基准角（两指连线的 atan2 弧度）；非双指态为 NaN，POINTER_DOWN 建立、UP 复位，防丢帧跳变 */
    private var lastPinchAngleRad = Double.NaN

    /** 双指俯仰基准：两指质心 Y（像素）；非双指态为 NaN，建基准/复位同 [lastPinchAngleRad] */
    private var lastPinchCentroidY = Float.NaN

    /** tilt 灵敏度（度/像素）：≈300px 行程扫完 0..75° 全程（主流地图 App 同量级） */
    private val tiltDegPerPixel = 0.25

    /** 两指连线夹角（弧度）。屏幕 y 向下 → 该角随手指「视觉顺时针」旋转而增大 */
    private fun pinchAngleRad(event: MotionEvent): Double = Math.atan2(
        (event.getY(1) - event.getY(0)).toDouble(),
        (event.getX(1) - event.getX(0)).toDouble()
    )

    /** 两指质心 Y（像素）：tilt 手势的纵向位移基准 */
    private fun pinchCentroidY(event: MotionEvent): Float =
        (event.getY(0) + event.getY(1)) * 0.5f

    /** fling 逐帧回调：取 OverScroller 当前位移增量透传平移并请求重绘，直到滑行结束 */
    private val flingFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!flingRunning) return
            if (scroller.computeScrollOffset()) {
                val x = scroller.currX
                val y = scroller.currY
                val dx = x - lastFlingX
                val dy = y - lastFlingY
                lastFlingX = x
                lastFlingY = y
                if (sink.isReady() && (dx != 0 || dy != 0)) {
                    sink.panBy(dx.toDouble(), dy.toDouble())
                }
                sink.requestRender()
                choreographer.postFrameCallback(this)
            } else {
                flingRunning = false
            }
        }
    }

    /** 拖动平移 + 双击放大：像素位移透传（onScroll 的 distance 与手指位移反向，故取负） */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            // 按下
            override fun onDown(e: MotionEvent): Boolean = true

            // 单击
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke(e.x, e.y)
                return true
            }

            // 滑动平移：取 gesture 的像素位移量（与手指位移反向，故取负）
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (scaleDetector.isInProgress) return false // 缩放进行中不平移，避免抢焦点
                // 3D 下双指纵向拖拽让位 tilt 俯仰（WorldWind/主流地图惯例），单指平移不受影响
                if (e2.pointerCount >= 2 && sink.is3DMode()) return false
                if (sink.isReady()) {
                    sink.panBy(-distanceX.toDouble(), -distanceY.toDouble())
                    sink.requestRender()
                }
                return true
            }

            // 双击放大：以点击点为锚放大 2 倍
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (sink.isReady()) {
                    sink.zoomBy(2.0, e.x, e.y)
                    sink.requestRender()
                }
                return true
            }

            // 惯性滑动：以松手速度为初速度减速滑行
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (scaleDetector.isInProgress) return false // 缩放进行中不触发惯性
                // 3D 双指手势（tilt/旋转）收尾不触发平移惯性，防抬手瞬间地图意外滑行
                if (e2.pointerCount >= 2 && sink.is3DMode()) return false
                if (!sink.isReady()) return false
                // 惯性滑行：以松手速度为初速度，OverScroller 按默认摩擦减速；逐帧把位移增量透传平移。
                // 速度符号与 panBy 一致（正 = 内容随手指方向移动），故直接传入。
                scroller.forceFinished(true)
                scroller.fling(
                    0, 0, velocityX.toInt(), velocityY.toInt(),
                    Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE
                )
                lastFlingX = 0
                lastFlingY = 0
                flingRunning = true
                choreographer.postFrameCallback(flingFrame)
                return true
            }
        }
    )

    /**
     * 触摸事件入口：分发捏合缩放与拖动/双击手势识别器（手势只更新相机状态，是否重绘由各回调决定），
     * 并在分发前自行识别双指旋转与双指俯仰（ScaleGestureDetector 无旋转/俯仰输出：两指连线夹角逐帧
     * 增量直供 [GestureSink.rotateBy]，两指质心 Y 逐帧增量经灵敏度换算供 [GestureSink.tiltBy]（仅 3D），
     * 与缩放/平移可同时进行，同真实地图 App 双指操作习惯）。
     * 由 [NativeMapView.onTouchEvent] 转发。
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // 手指按下即停住惯性滑行，交回手动控制
            MotionEvent.ACTION_DOWN -> cancelFling()
            // 第二指落下：以本帧两指连线角/质心 Y 为旋转与俯仰基准；抬起任一指/全部抬起即复位
            MotionEvent.ACTION_POINTER_DOWN ->
                if (event.pointerCount == 2) {
                    lastPinchAngleRad = pinchAngleRad(event)
                    lastPinchCentroidY = pinchCentroidY(event)
                }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                lastPinchAngleRad = Double.NaN
                lastPinchCentroidY = Float.NaN
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 2) {
            // 双指俯仰（仅 3D）：质心 Y 逐帧增量换算倾角——上推（dyPx<0）向地平线倾视、下拉回正，
            // 同主流地图 App 惯例；与双指旋转/捏合共用同一事件序列，互不排斥
            if (sink.is3DMode()) {
                val cy = pinchCentroidY(event)
                if (lastPinchCentroidY.isNaN()) {
                    lastPinchCentroidY = cy // 未捕到 POINTER_DOWN 基准（如多指转双指），本帧仅建基准不倾视
                } else {
                    val tiltDeltaDeg = -(cy - lastPinchCentroidY) * tiltDegPerPixel
                    lastPinchCentroidY = cy
                    if (tiltDeltaDeg != 0.0 && sink.isReady()) {
                        sink.tiltBy(tiltDeltaDeg.toDouble())
                        sink.requestRender()
                    }
                }
            }
            val angle = pinchAngleRad(event)
            if (lastPinchAngleRad.isNaN()) {
                lastPinchAngleRad = angle // 未捕到 POINTER_DOWN 基准（如多指转双指），本帧仅建立基准不旋转
            } else {
                var deltaDeg = Math.toDegrees(angle - lastPinchAngleRad)
                lastPinchAngleRad = angle
                // 跨 ±180° 环绕的回跳：增量归一到 (-180, 180]，防临界帧 360° 大跳
                if (deltaDeg > 180.0) deltaDeg -= 360.0
                if (deltaDeg <= -180.0) deltaDeg += 360.0
                if (deltaDeg != 0.0 && sink.isReady()) {
                    // 两指连线 atan2 角增大（deltaDeg>0）对应手指视觉顺时针旋转；经真机验证，
                    // heading 增大时内容顺时针转（相机相对地转反向），故直接透传 deltaDeg 即内容跟随手指
                    sink.rotateBy(deltaDeg)
                    sink.requestRender()
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** 停掉惯性滑行的逐帧回调（视图销毁/手势重按时调用） */
    fun cancelFling() {
        scroller.forceFinished(true)
        flingRunning = false
    }
}
