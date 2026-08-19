package ceui.pixiv.ui.translate

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import ceui.lisa.R
import ceui.pixiv.utils.setOnClick
import kotlin.math.abs

/**
 * 「翻译整部」(issue #925)的机内悬浮小窗控制器:绑定 `view_manga_batch_translate_float`,
 * 把 [MangaBatchTranslateCenter.BatchStatus] 渲染成「第 N/M 页 + 当前阶段 + 逐页分段进度条 + 总百分比 + 作品名」,
 * 并让整张卡片在父布局内可拖动。
 *
 * 卡片由 [MangaBatchFloatInstaller] 挂在每个 Activity 的 `android.R.id.content` 上,
 * 和页面自己的布局是兄弟而非父子,所以 DOWN 时只需向祖先 requestDisallowInterceptTouchEvent,
 * 不会有哪个页面级 ViewGroup 能把拖动手势抢走。
 *
 * @param onTap 点卡片本体(非拖动)
 * @param onMoved 拖动结束后的 translation,宿主记住它让下一个 Activity 的卡片落在同一位置
 */
class MangaBatchTranslateFloatCard(
    private val root: View,
    onCancel: () -> Unit,
    onTap: () -> Unit,
    private val onMoved: (tx: Float, ty: Float) -> Unit,
) {
    private val stage: TextView = root.findViewById(R.id.manga_batch_stage)
    private val counter: TextView = root.findViewById(R.id.manga_batch_counter)
    private val percent: TextView = root.findViewById(R.id.manga_batch_percent)
    private val title: TextView = root.findViewById(R.id.manga_batch_title)
    private val strip: BatchPageStripView = root.findViewById(R.id.manga_batch_strip)

    init {
        root.findViewById<View>(R.id.manga_batch_cancel).setOnClick { onCancel() }
        root.setOnClickListener { onTap() }
        installDrag()
    }

    /** 正在播退场动画(alpha→0 后收成 GONE);用它而不是 alpha<1 判断,因为进场前 320ms alpha 也 <1。 */
    private var hiding = false

    /** null → 缩小淡出隐藏;非 null → (首次)放大淡入并刷新计数 / 阶段 / 分段条 / 百分比。 */
    fun render(status: MangaBatchTranslateCenter.BatchStatus?) {
        if (status == null) {
            if (root.visibility == View.VISIBLE && !hiding) {
                hiding = true
                root.animate().cancel()
                root.animate()
                    .alpha(0f).scaleX(0.92f).scaleY(0.92f)
                    .setDuration(220)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        hiding = false
                        root.visibility = View.GONE
                    }
                    .start()
            }
            return
        }
        if (root.visibility != View.VISIBLE) {
            root.animate().cancel()
            root.alpha = 0f
            root.scaleX = 0.9f
            root.scaleY = 0.9f
            root.visibility = View.VISIBLE
            root.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(320)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        } else if (hiding) {
            // 上一批刚结束的退场还没播完又来新一批:掐掉退场,别让卡片最终被 withEndAction 收成 GONE。
            // 进场动画进行中来的状态刷新(很常见,阶段切得快)不走这里,让进场自然播完。
            hiding = false
            root.animate().cancel()
            root.alpha = 1f
            root.scaleX = 1f
            root.scaleY = 1f
        }
        val current = (status.pageDone + 1).coerceAtMost(status.total)
        counter.text = buildCounter(current, status.total)
        stage.text = status.stageText
        title.text = status.title
        val inPage = status.stagePercent?.let { it / 100f }
        strip.setProgress(status.total, status.pageDone, inPage)
        // 总进度 = 已完成页 + 当前页内阶段占比;阶段没百分比时只算整页,数字不回退
        val overall = ((status.pageDone + (inPage ?: 0f)) / status.total.coerceAtLeast(1) * 100)
            .toInt().coerceIn(0, 100)
        percent.text = "$overall%"
    }

    /** 「3 / 12」:当前页 18sp 白色粗体,「/ 12」12sp 半透明,一眼先看到在第几页。 */
    private fun buildCounter(current: Int, total: Int): CharSequence {
        val head = current.toString()
        val tail = " / $total"
        return SpannableStringBuilder(head + tail).apply {
            setSpan(AbsoluteSizeSpan(18, true), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(0x80FFFFFF.toInt()),
                head.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag() {
        val slop = ViewConfiguration.get(root.context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startTx = 0f
        var startTy = 0f
        var moved = false
        root.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startTx = v.translationX
                    startTy = v.translationY
                    moved = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        val parent = v.parent as? ViewGroup
                        var tx = startTx + dx
                        var ty = startTy + dy
                        if (parent != null) {
                            // 限制在父布局内,别拖出屏幕回不来
                            tx = tx.coerceIn(-v.left.toFloat(), (parent.width - v.right).toFloat())
                            ty = ty.coerceIn(-v.top.toFloat(), (parent.height - v.bottom).toFloat())
                        }
                        v.translationX = tx
                        v.translationY = ty
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) onMoved(v.translationX, v.translationY)
                    else if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                else -> false
            }
        }
    }
}
