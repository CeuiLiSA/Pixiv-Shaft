package ceui.pixiv.ui.translate

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import ceui.lisa.R
import ceui.lisa.activities.ImageTranslationViewModel
import ceui.pixiv.utils.setOnClick
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.abs

/**
 * 「翻译整部」(issue #925)的机内悬浮小窗控制器:绑定 `view_manga_batch_translate_float`,
 * 把 [ImageTranslationViewModel.BatchStatus] 渲染成「第 N/M 页 + 当前阶段 + 总进度条」,
 * 并让整张卡片在父布局内可拖动。
 *
 * 拖动与 DragDismissLayout 的配合:DOWN 时向祖先 requestDisallowInterceptTouchEvent,
 * 同时通过 [onDragging] 通知宿主 —— 宿主在 `canStartDismissDrag` 里据此返回 false,
 * DragDismissLayout 就不会在竖向越过 slop 时把手势抢去做下拉关闭。
 */
class MangaBatchTranslateFloatCard(
    private val root: View,
    onCancel: () -> Unit,
    private val onDragging: (Boolean) -> Unit,
) {
    private val title: TextView = root.findViewById(R.id.manga_batch_title)
    private val stage: TextView = root.findViewById(R.id.manga_batch_stage)
    private val progress: LinearProgressIndicator = root.findViewById(R.id.manga_batch_progress)

    init {
        root.findViewById<View>(R.id.manga_batch_cancel).setOnClick { onCancel() }
        installDrag()
    }

    /** null → 淡出隐藏;非 null → 显示并刷新三件套。 */
    fun render(status: ImageTranslationViewModel.BatchStatus?) {
        if (status == null) {
            if (root.visibility == View.VISIBLE) {
                root.animate().alpha(0f).setDuration(200).withEndAction {
                    root.visibility = View.GONE
                }.start()
            }
            return
        }
        if (root.visibility != View.VISIBLE) {
            root.alpha = 0f
            root.visibility = View.VISIBLE
            root.animate().alpha(1f).setDuration(200).start()
        } else if (root.alpha < 1f) {
            // 上一批刚结束的淡出还没播完又来新一批:掐掉淡出,别让卡片最终被 withEndAction 收成 GONE
            root.animate().cancel()
            root.alpha = 1f
        }
        val ctx = root.context
        val current = (status.pageDone + 1).coerceAtMost(status.total)
        title.text = ctx.getString(R.string.string_ai_manga_batch_progress_title, current, status.total)
        stage.text = status.stageText
        // 总进度 = 已完成页 + 当前页内阶段占比;阶段没百分比时只算整页,条子不回退
        val inPage = (status.stagePercent ?: 0) / 100f
        val overall = ((status.pageDone + inPage) / status.total.coerceAtLeast(1) * 100).toInt().coerceIn(0, 100)
        progress.setProgressCompat(overall, true)
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
                    onDragging(true)
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
                    onDragging(false)
                    if (ev.actionMasked == MotionEvent.ACTION_UP && !moved) v.performClick()
                    true
                }
                else -> false
            }
        }
    }
}
