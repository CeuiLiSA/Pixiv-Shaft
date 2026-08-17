package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitDisplay

/**
 * 所有弹窗 builder 的基类，替代 `QMUIDialogBuilder`。
 *
 * 公开 API 逐字镜像 QMUI（方法名、重载、返回值、**以及「按钮点击不自动 dismiss」这条语义**），
 * 所以调用点的迁移应该是纯改名 —— `git diff --word-diff` 上只该看到类名在变。
 *
 * 与 QMUI 的实现差异（不影响调用方）：
 * - 卡片是竖向 [LinearLayout] 而不是 `ConstraintLayout`，见 [WitDialogView] 的说明。
 * - 皮肤系统整个不存在。QMUI 的 `setSkinManager` 在本项目里是死代码
 *   （123 处调用，但没有 skin XML、没有初始化、没有一次 changeSkin），
 *   这里保留成 no-op（见 `WitSkinManager`），迁移完就删。
 */
public abstract class WitDialogBuilder<T : WitDialogBuilder<T>>(
    protected val context: Context,
) {

    public companion object {
        public const val HORIZONTAL: Int = 0
        public const val VERTICAL: Int = 1
    }

    /** 从 `?attr/colorPrimary` 派生的调色板。弹窗里一切强调色都来自这里，绝不写死。 */
    protected val palette: V3Palette = V3Palette.from(context)

    protected var mTitle: CharSequence? = null
    protected val mActions: MutableList<WitDialogAction> = mutableListOf()
    protected var mDialog: WitDialog? = null

    private var cancelable: Boolean = true
    private var canceledOnTouchOutside: Boolean = true
    private var actionContainerOrientation: Int = HORIZONTAL

    @Suppress("UNCHECKED_CAST")
    private fun self(): T = this as T

    // ── 配置 ────────────────────────────────────────────────────────

    public fun setTitle(title: CharSequence?): T = self().also { mTitle = title }

    public fun setTitle(@StringRes resId: Int): T = setTitle(context.resources.getString(resId))

    public fun setCancelable(cancelable: Boolean): T = self().also { this.cancelable = cancelable }

    public fun setCanceledOnTouchOutside(canceledOnTouchOutside: Boolean): T =
        self().also { this.canceledOnTouchOutside = canceledOnTouchOutside }

    /** [HORIZONTAL]（默认，右对齐一行）或 [VERTICAL]（整宽竖向堆叠，按钮文案长时用）。 */
    public fun setActionContainerOrientation(orientation: Int): T =
        self().also { actionContainerOrientation = orientation }

    /**
     * QMUI 皮肤管理器的兼容位。**实现为 no-op**，只为让重命名那一步是纯 token 交换；
     * 迁移完成后这个方法和它的 123 处调用会被一并删掉。
     */
    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    @Deprecated("皮肤系统在本项目里从未启用，迁移完成后会删除")
    public fun setSkinManager(skinManager: WitSkinManager?): T = self()

    // ── addAction 全家（重载与 QMUI 一一对应）───────────────────────

    public fun addAction(action: WitDialogAction?): T = self().also {
        if (action != null) mActions.add(action)
    }

    public fun addAction(@StringRes strResId: Int, listener: WitDialogAction.ActionListener?): T =
        addAction(0, strResId, WitDialogAction.ACTION_PROP_NEUTRAL, listener)

    public fun addAction(str: CharSequence?, listener: WitDialogAction.ActionListener?): T =
        addAction(0, str, WitDialogAction.ACTION_PROP_NEUTRAL, listener)

    public fun addAction(
        iconResId: Int,
        @StringRes strResId: Int,
        listener: WitDialogAction.ActionListener?,
    ): T = addAction(iconResId, strResId, WitDialogAction.ACTION_PROP_NEUTRAL, listener)

    public fun addAction(
        iconResId: Int,
        str: CharSequence?,
        listener: WitDialogAction.ActionListener?,
    ): T = addAction(iconResId, str, WitDialogAction.ACTION_PROP_NEUTRAL, listener)

    public fun addAction(
        iconRes: Int,
        @StringRes strRes: Int,
        prop: Int,
        listener: WitDialogAction.ActionListener?,
    ): T = addAction(iconRes, context.resources.getString(strRes), prop, listener)

    public fun addAction(
        iconRes: Int,
        str: CharSequence?,
        prop: Int,
        listener: WitDialogAction.ActionListener?,
    ): T = self().also {
        mActions.add(WitDialogAction(str).iconRes(iconRes).prop(prop).onClick(listener))
    }

    // ── 组装 ────────────────────────────────────────────────────────

    public fun show(): WitDialog = create().also { it.show() }

    @JvmOverloads
    public fun create(@StyleRes style: Int = R.style.ThemeOverlay_Wit_Dialog): WitDialog {
        val dialog = WitDialog(context, style)
        mDialog = dialog
        // 用 dialog 自己的 context：它是套了本模块 overlay 的 ContextThemeWrapper，
        // 而 ContextThemeWrapper 是**合并**语义，host 的 colorPrimary 原样活着。
        val dialogContext = dialog.context

        val dialogView = WitDialogView(dialogContext)
        val rootLayout = WitDialogRootLayout(
            dialogContext,
            dialogView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        val titleView = onCreateTitle(dialog, dialogView, dialogContext)
        val contentView = onCreateContent(dialog, dialogView, dialogContext)
        val operatorView = onCreateOperatorLayout(dialog, dialogView, dialogContext)

        if (titleView != null) {
            if (contentView == null) {
                // 没有内容区时，标题得自己补出本该由内容区提供的下方留白，
                // 否则标题会直接贴着按钮。对应 QMUI 的 qmui_paddingBottomWhenNotContent。
                titleView.setPadding(
                    titleView.paddingLeft,
                    titleView.paddingTop,
                    titleView.paddingRight,
                    titleView.paddingBottom + WitDisplay.dp2px(
                        dialogContext,
                        WitDialogMetrics.TITLE_PADDING_BOTTOM_WHEN_NO_CONTENT_DP,
                    ),
                )
            }
            dialogView.addView(
                titleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (contentView != null) {
            // weight=1 + wrap_content：内容不高时不拉伸（delta=0），
            // 高到顶满 85% 屏高时才把负 delta 摊到这里 —— 于是只有内容区滚动。
            dialogView.addView(
                contentView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
        }
        if (operatorView != null) {
            dialogView.addView(
                operatorView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        dialog.addContentView(
            rootLayout,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(canceledOnTouchOutside)
        onAfterCreate(dialog, rootLayout, dialogContext)
        return dialog
    }

    // ── 扩展点 ──────────────────────────────────────────────────────

    protected open fun hasTitle(): Boolean = !mTitle.isNullOrEmpty()

    protected open fun onCreateTitle(
        dialog: WitDialog,
        parent: WitDialogView,
        context: Context,
    ): View? {
        if (!hasTitle()) return null
        return AppCompatTextView(context).apply {
            text = mTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, WitDialogMetrics.TITLE_TEXT_SP)
            setTextColor(ContextCompat.getColor(context, R.color.wit_text_1))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val padH = WitDisplay.dp2px(context, WitDialogMetrics.PADDING_HORIZONTAL_DP)
            setPadding(
                padH,
                WitDisplay.dp2px(context, WitDialogMetrics.TITLE_PADDING_TOP_DP),
                padH,
                0,
            )
        }
    }

    protected abstract fun onCreateContent(
        dialog: WitDialog,
        parent: WitDialogView,
        context: Context,
    ): View?

    protected open fun onCreateOperatorLayout(
        dialog: WitDialog,
        parent: WitDialogView,
        context: Context,
    ): View? {
        if (mActions.isEmpty()) return null
        val vertical = actionContainerOrientation == VERTICAL
        val container = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.END
            val padH = WitDisplay.dp2px(
                context, WitDialogMetrics.ACTION_CONTAINER_PADDING_HORIZONTAL_DP
            )
            setPadding(
                padH, 0, padH,
                WitDisplay.dp2px(context, WitDialogMetrics.ACTION_CONTAINER_PADDING_BOTTOM_DP),
            )
        }
        val height = WitDisplay.dp2px(context, WitDialogMetrics.ACTION_HEIGHT_DP)
        val space = WitDisplay.dp2px(context, WitDialogMetrics.ACTION_SPACE_DP)
        mActions.forEachIndexed { index, action ->
            val actionView = action.buildActionView(dialog, index, palette)
            val lp = if (vertical) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                    if (index > 0) topMargin = space
                }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height).apply {
                    if (index > 0) marginStart = space
                }
            }
            container.addView(actionView, lp)
        }
        if (!vertical) {
            // 文案过长排不下时，同步收窄所有按钮的横向 padding（沿用 QMUI 的兜底思路）。
            // 真要放不下的场景，调用方应该显式传 VERTICAL。
            container.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
                val width = right - left
                val last = (v as LinearLayout).getChildAt(v.childCount - 1) ?: return@addOnLayoutChangeListener
                if (last.right > width) {
                    val shrunk = (last.paddingLeft - WitDisplay.dp2px(context, 3)).coerceAtLeast(0)
                    for (i in 0 until v.childCount) {
                        v.getChildAt(i).setPadding(shrunk, 0, shrunk, 0)
                    }
                }
            }
        }
        return container
    }

    protected open fun onAfterCreate(
        dialog: WitDialog,
        rootLayout: WitDialogRootLayout,
        context: Context,
    ) {
    }

    /** 把内容区包进可滚动容器。内容超高时只有它滚动，标题和按钮保持可见。 */
    protected fun wrapWithScroll(view: View): View =
        NestedScrollView(view.context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
}
