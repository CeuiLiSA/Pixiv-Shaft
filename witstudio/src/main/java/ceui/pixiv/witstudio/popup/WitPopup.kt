package ceui.pixiv.witstudio.popup

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.annotation.ColorInt
import androidx.annotation.LayoutRes
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitDisplay

/**
 * 锚定弹出层，替代 `QMUIPopup`。链式 API 与 QMUI 一一对应，迁移只需改类名。
 *
 * 全 app 此前没有一处直接用 `android.widget.PopupWindow`，弹出层全部经由 QMUI，
 * 所以这是本次迁移里唯一真正从零写的部分（定位、贴边钳制、dim、动画都在这）。
 *
 * 视觉走 V3：实底圆角卡 + hairline + 柔和阴影，底色默认取 [V3Palette.cardFill] 跟随主题色。
 */
public class WitPopup(private val context: Context) {

    public companion object {
        public const val DIRECTION_TOP: Int = 0
        public const val DIRECTION_BOTTOM: Int = 1

        public const val ANIM_GROW_FROM_CENTER: Int = 0
        public const val ANIM_GROW_FROM_LEFT: Int = 1
        public const val ANIM_GROW_FROM_RIGHT: Int = 2

        private const val DEFAULT_RADIUS_DP = 20
        private const val DEFAULT_SHADOW_DP = 16
    }

    private val palette = V3Palette.from(context)

    private var contentView: View? = null
    private var preferredDirection: Int = DIRECTION_BOTTOM
    private var dimAmount: Float = 0f
    private var edgeProtectionPx: Int = WitDisplay.dp2px(context, 8)
    private var offsetXPx: Int = 0
    private var offsetYIfBottomPx: Int = 0
    private var offsetYIfTopPx: Int = 0
    private var shadow: Boolean = true
    private var radiusPx: Int = WitDisplay.dp2px(context, DEFAULT_RADIUS_DP)
    private var animStyle: Int = ANIM_GROW_FROM_CENTER
    private var dismissListener: PopupWindow.OnDismissListener? = null

    @ColorInt
    private var bgColor: Int = palette.cardFill

    /** 固定内容宽 / 最大高。0 表示按内容自适应。[WitPopups.listPopup] 会用到。 */
    private var fixedWidthPx: Int = 0
    private var maxHeightPx: Int = 0

    private var popupWindow: PopupWindow? = null

    public fun view(view: View): WitPopup = apply { contentView = view }

    public fun view(@LayoutRes layoutId: Int): WitPopup =
        apply { contentView = LayoutInflater.from(context).inflate(layoutId, null) }

    public fun preferredDirection(direction: Int): WitPopup = apply { preferredDirection = direction }

    public fun dimAmount(amount: Float): WitPopup = apply { dimAmount = amount.coerceIn(0f, 1f) }

    public fun edgeProtection(px: Int): WitPopup = apply { edgeProtectionPx = px }

    public fun offsetX(px: Int): WitPopup = apply { offsetXPx = px }

    public fun offsetYIfBottom(px: Int): WitPopup = apply { offsetYIfBottomPx = px }

    public fun offsetYIfTop(px: Int): WitPopup = apply { offsetYIfTopPx = px }

    public fun shadow(enabled: Boolean): WitPopup = apply { shadow = enabled }

    public fun radius(px: Int): WitPopup = apply { radiusPx = px }

    public fun bgColor(@ColorInt color: Int): WitPopup = apply { bgColor = color }

    public fun animStyle(style: Int): WitPopup = apply { animStyle = style }

    public fun onDismiss(listener: PopupWindow.OnDismissListener?): WitPopup =
        apply { dismissListener = listener }

    /**
     * QMUI 的箭头。**实现为 no-op**：全 app 只有一处传 true（长按插画卡的菜单），
     * 而 V3 语汇里没有任何地方用箭头指示器 —— 为一个菜单写贴边翻转 + 描边开口的边界数学不划算。
     * 保留签名只为让迁移是纯改名。
     */
    @Suppress("UNUSED_PARAMETER")
    public fun arrow(enabled: Boolean): WitPopup = this

    internal fun contentSize(widthPx: Int, maxHeightPx: Int): WitPopup = apply {
        this.fixedWidthPx = widthPx
        this.maxHeightPx = maxHeightPx
    }

    public val isShowing: Boolean
        get() = popupWindow?.isShowing == true

    public fun dismiss() {
        popupWindow?.dismiss()
    }

    /** 锚定到 [anchor] 弹出。会自动选上/下方向并把左右钳进屏幕安全区。 */
    public fun show(anchor: View): WitPopup = apply {
        val content = contentView ?: return@apply

        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx.toFloat()
                setColor(bgColor)
                // 近乎零 elevation 的 V3 语汇：层次主要靠 hairline，阴影只是很轻的一层补充。
                setStroke(
                    (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    palette.cardHairline
                )
            }
            clipToOutline = true
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val maxW = (screenW - 2 * edgeProtectionPx).coerceAtLeast(1)
        val widthSpec = if (fixedWidthPx > 0) {
            View.MeasureSpec.makeMeasureSpec(fixedWidthPx, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST)
        }
        val heightBound = if (maxHeightPx > 0) minOf(maxHeightPx, screenH) else screenH
        container.measure(
            widthSpec,
            View.MeasureSpec.makeMeasureSpec(heightBound, View.MeasureSpec.AT_MOST)
        )
        val popupW = if (fixedWidthPx > 0) fixedWidthPx else container.measuredWidth.coerceAtMost(maxW)
        val popupH = container.measuredHeight.coerceAtMost(heightBound)

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val anchorTop = loc[1]
        val anchorBottom = anchorTop + anchor.height

        // 方向选择：优先按 preferredDirection，放不下就翻到另一侧；两边都放不下时选空间大的那边。
        val spaceBelow = screenH - anchorBottom - edgeProtectionPx
        val spaceAbove = anchorTop - edgeProtectionPx
        val showBelow = if (preferredDirection == DIRECTION_BOTTOM) {
            popupH <= spaceBelow || spaceBelow >= spaceAbove
        } else {
            !(popupH <= spaceAbove || spaceAbove > spaceBelow)
        }

        val y = if (showBelow) {
            anchorBottom + offsetYIfBottomPx
        } else {
            (anchorTop - popupH - offsetYIfTopPx).coerceAtLeast(edgeProtectionPx)
        }
        // 贴边保护：左右都钳进 [edgeProtection, 屏宽-弹窗宽-edgeProtection]，
        // 屏幕窄到装不下时 coerceAtLeast 兜底，避免出现上界小于下界的崩溃。
        val rawX = loc[0] + offsetXPx
        val x = rawX.coerceIn(
            edgeProtectionPx,
            (screenW - popupW - edgeProtectionPx).coerceAtLeast(edgeProtectionPx)
        )

        val window = PopupWindow(container, popupW, popupH, true).apply {
            // 必须给一个非空 background，否则点外部不关闭、也没有阴影。
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = if (shadow) {
                WitDisplay.dp2px(context, DEFAULT_SHADOW_DP).toFloat()
            } else {
                0f
            }
            animationStyle = when (animStyle) {
                ANIM_GROW_FROM_LEFT -> R.style.Animation_Wit_Popup_GrowFromLeft
                ANIM_GROW_FROM_RIGHT -> R.style.Animation_Wit_Popup_GrowFromRight
                else -> R.style.Animation_Wit_Popup_GrowFromCenter
            }
            setOnDismissListener { dismissListener?.onDismiss() }
        }
        popupWindow = window
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        applyDimIfNeeded(container)
    }

    /**
     * PopupWindow 没有现成的「压暗背景」开关，只能拿到它自己那层 window 的 LayoutParams
     * 加 FLAG_DIM_BEHIND 再让 WindowManager 重新排一次。必须在 show 之后做 —— 之前
     * decorView 还没 attach，layoutParams 拿不到。
     */
    private fun applyDimIfNeeded(container: View) {
        if (dimAmount <= 0f) return
        val decor = container.rootView
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val lp = decor.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        lp.dimAmount = dimAmount
        runCatching { wm.updateViewLayout(decor, lp) }
    }
}
