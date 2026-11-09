package ceui.lisa.helper

import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 1:1 复刻 Facebook Rebound (0.3.8) 的 SpringChain 物理动画引擎。
 * 剥离外部三方依赖，保留原版 RK4 积分公式与级联牵引弹性波浪质感。
 *
 * 优化特性：
 * 1. 自动生命周期解绑：容器 detach 时自动停止 Choreographer 循环，杜绝内存泄漏与后台空转；
 * 2. 防重入保护：WeakHashMap 记录在运行任务，重复触发时自动取消上一次未完帧回调；
 * 3. 首帧平滑锚定：首帧记录相对基准时间（dt=0.016s），避开启动瞬间时间跳变导致的位移突突；
 * 4. 零 GC 开销：物理计算全流程复用局部基本数据类型，无多余对象分配。
 */
object ReboundSpringChain {

    private const val SOLVER_TIMESTEP_SEC = 0.001
    private const val MAX_DELTA_TIME_SEC = 0.064
    private const val REST_THRESHOLD = 0.05

    // 原版默认 Origami 参数 (SpringChain.create(40, 8, 60, 10))
    private const val DEFAULT_MAIN_TENSION = 40.0
    private const val DEFAULT_MAIN_FRICTION = 8.0
    private const val DEFAULT_ATTACH_TENSION = 60.0
    private const val DEFAULT_ATTACH_FRICTION = 10.0
    private const val DEFAULT_INITIAL_OFFSET = 400.0

    private val activeTasks = WeakHashMap<View, Runnable>()

    /**
     * Rebound 核心四阶龙格-库塔 (RK4) 物理弹簧计算单元
     */
    private class PhysicsSpring(
        val tension: Double,
        val friction: Double,
        initialPos: Double
    ) {
        var pos = initialPos
        var vel = 0.0
        var endVal = initialPos
        private var timeAcc = 0.0

        fun advance(realDeltaSec: Double): Boolean {
            val dt = if (realDeltaSec > MAX_DELTA_TIME_SEC) MAX_DELTA_TIME_SEC else realDeltaSec
            timeAcc += dt

            while (timeAcc >= SOLVER_TIMESTEP_SEC) {
                timeAcc -= SOLVER_TIMESTEP_SEC
                val p = pos
                val v = vel

                // a 导数
                val aV = v
                val aA = (tension * (endVal - p)) - friction * v

                // b 导数 (中点)
                val tP1 = p + aV * SOLVER_TIMESTEP_SEC * 0.5
                val tV1 = v + aA * SOLVER_TIMESTEP_SEC * 0.5
                val bA = (tension * (endVal - tP1)) - friction * tV1

                // c 导数 (中点)
                val tP2 = p + tV1 * SOLVER_TIMESTEP_SEC * 0.5
                val tV2 = v + bA * SOLVER_TIMESTEP_SEC * 0.5
                val cA = (tension * (endVal - tP2)) - friction * tV2

                // d 导数 (端点)
                val tP3 = p + tV2 * SOLVER_TIMESTEP_SEC
                val tV3 = v + cA * SOLVER_TIMESTEP_SEC
                val dA = (tension * (endVal - tP3)) - friction * tV3

                // 加权综合斜率
                val dxdt = (aV + 2.0 * (tV1 + tV2) + tV3) / 6.0
                val dvdt = (aA + 2.0 * (bA + cA) + dA) / 6.0

                pos += dxdt * SOLVER_TIMESTEP_SEC
                vel += dvdt * SOLVER_TIMESTEP_SEC
            }

            // 停振阈值检测
            if (abs(vel) <= REST_THRESHOLD && abs(endVal - pos) <= REST_THRESHOLD) {
                pos = endVal
                vel = 0.0
                return true
            }
            return false
        }
    }

    /**
     * 对容器下的直接子 View 播放物理级联弹性入场动画。
     *
     * @param container 目标父容器（子项将按顺序依次连锁弹入）
     * @param initialOffset 初始位移（默认 400px，对齐 Rebound 原版参数）
     * @param mainTension 主弹簧张力（Origami 数值）
     * @param mainFriction 主弹簧阻尼（Origami 数值）
     * @param attachTension 级联牵引弹簧张力（Origami 数值）
     * @param attachFriction 级联牵引弹簧阻尼（Origami 数值）
     */
    @JvmStatic
    @JvmOverloads
    fun animate(
        container: ViewGroup,
        initialOffset: Float = DEFAULT_INITIAL_OFFSET.toFloat(),
        mainTension: Double = DEFAULT_MAIN_TENSION,
        mainFriction: Double = DEFAULT_MAIN_FRICTION,
        attachTension: Double = DEFAULT_ATTACH_TENSION,
        attachFriction: Double = DEFAULT_ATTACH_FRICTION
    ) {
        // 取消该容器上未完成的旧动画任务，防止多重触发竞态
        activeTasks[container]?.run()

        // onCreateView 调用时尚未 attach；post 会晚于首帧，入场初态必须同步就绪。
        for (i in 0 until container.childCount) {
            container.getChildAt(i).translationX = initialOffset
        }

        val postRunnable = Runnable {
            if (!container.isAttachedToWindow && container.windowToken == null) {
                activeTasks.remove(container)
                return@Runnable
            }

            val count = container.childCount
            if (count == 0) {
                activeTasks.remove(container)
                return@Runnable
            }

            // Origami 物理张力换算公式：
            // tension = (origamiTension - 30.0) * 3.62 + 194.0
            // friction = (origamiFriction - 8.0) * 3.0 + 25.0
            val convertedMainTension = if (mainTension == 0.0) 0.0 else (mainTension - 30.0) * 3.62 + 194.0
            val convertedMainFriction = if (mainFriction == 0.0) 0.0 else (mainFriction - 8.0) * 3.0 + 25.0
            val convertedAttachTension = if (attachTension == 0.0) 0.0 else (attachTension - 30.0) * 3.62 + 194.0
            val convertedAttachFriction = if (attachFriction == 0.0) 0.0 else (attachFriction - 8.0) * 3.0 + 25.0

            val springs = Array(count) { i ->
                val initPos = initialOffset.toDouble()
                if (i == 0) {
                    PhysicsSpring(convertedMainTension, convertedMainFriction, initPos)
                } else {
                    PhysicsSpring(convertedAttachTension, convertedAttachFriction, initPos)
                }
            }
            springs[0].endVal = 0.0

            val views = Array(count) { i ->
                container.getChildAt(i)
            }

            val choreographer = Choreographer.getInstance()
            var lastTimeMs = 0L
            var isRunning = true

            val frameCallback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!isRunning || !container.isAttachedToWindow) {
                        return
                    }

                    val nowMs = SystemClock.uptimeMillis()
                    val dtSec = if (lastTimeMs == 0L) {
                        0.016
                    } else {
                        ((nowMs - lastTimeMs).coerceIn(1L, 64L)) / 1000.0
                    }
                    lastTimeMs = nowMs

                    var allAtRest = true

                    for (i in 0 until count) {
                        val spring = springs[i]
                        val isRest = spring.advance(dtSec)
                        if (!isRest) {
                            allAtRest = false
                        }
                        // 级联跟随：后一个 View 的目标值实时取前一个 View 的瞬时坐标
                        if (i < count - 1) {
                            springs[i + 1].endVal = spring.pos
                        }
                        views[i].translationX = spring.pos.toFloat()
                    }

                    if (!allAtRest) {
                        choreographer.postFrameCallback(this)
                    } else {
                        isRunning = false
                        activeTasks.remove(container)
                        for (i in 0 until count) {
                            views[i].translationX = 0f
                        }
                    }
                }
            }

            // 生命周期监听：离开屏幕时自动解绑，防止泄露与多余计算
            val attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    isRunning = false
                    choreographer.removeFrameCallback(frameCallback)
                    container.removeOnAttachStateChangeListener(this)
                    activeTasks.remove(container)
                }
            }
            container.addOnAttachStateChangeListener(attachListener)

            // 注册取消回调给 activeTasks
            val cancelRunnable = Runnable {
                isRunning = false
                choreographer.removeFrameCallback(frameCallback)
                container.removeOnAttachStateChangeListener(attachListener)
            }
            activeTasks[container] = cancelRunnable

            choreographer.postFrameCallback(frameCallback)
        }

        container.post(postRunnable)
    }
}
