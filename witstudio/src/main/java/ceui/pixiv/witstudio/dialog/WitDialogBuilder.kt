package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitDisplay
import kotlin.math.roundToInt

/**
 * 所有弹窗 builder 的基类，替代 `QMUIDialogBuilder`。
 *
 * 公开 API 逐字镜像 QMUI（方法名、重载、返回值、**以及「按钮点击不自动 dismiss」这条语义**），
 * 所以调用点的迁移应该是纯改名 —— `git diff --word-diff` 上只该看到类名在变。
 *
 * 与 QMUI 的实现差异（不影响调用方）：
 * - 卡片是竖向 [LinearLayout] 而不是 `ConstraintLayout`，见 [WitDialogView] 的说明。
 * - 皮肤系统整个不存在。QMUI 的 `setSkinManager` 在本项目里是死代码（123 处调用，
 *   但没有 skin XML、没有初始化、没有一次 changeSkin），迁移时已一并删除，
 *   日夜完全由模块自带的 values-night 承担。
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

    /** 标题右侧的图标动作（帮助 / 网络测试这类轻型动作），见 [addTitleAction]。 */
    private class TitleAction(
        @DrawableRes val iconRes: Int,
        val contentDescription: CharSequence?,
        val listener: View.OnClickListener?,
    )

    private val mTitleActions = mutableListOf<TitleAction>()

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
     * 往标题右侧加一个图标按钮 —— 「帮助」「网络测试」这类不占按钮位的轻型动作。
     *
     * 可以加多个：按调用顺序**从左到右**排，最右边那个（最后调用的）的图形右缘与标题左缘
     * 共用同一条 `24dp` 栏距 —— 图形按 [WitDialogMetrics.TITLE_ACTION_GRAPHIC_DP] 的 24dp 画，
     * 外面套 [WitDialogMetrics.TITLE_ACTION_PADDING_DP] 的内衬凑到 40dp 热区（够到 MD3 触控下限）。
     * 标题的文字区会自行让开这一串图标，长标题不会钻到图标底下。
     *
     * 调用方若自己覆写 [onCreateTitle] 塞图标，请改走这里，别再手搓 FrameLayout。
     */
    public fun addTitleAction(
        @DrawableRes iconRes: Int,
        contentDescription: CharSequence?,
        listener: View.OnClickListener?,
    ): T = self().also {
        mTitleActions.add(TitleAction(iconRes, contentDescription, listener))
    }

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
        val title = AppCompatTextView(context).apply {
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
        if (mTitleActions.isEmpty()) return title
        return wrapTitleWithActions(title, context)
    }

    /**
     * 把标题和右侧那一串图标装进同一个容器。
     *
     * 标题自带「左右各 24dp + 顶部 24dp」的内边距。直接塞进 [FrameLayout] 再让图标 CENTER_VERTICAL，
     * 居中的就是「文字 + 24dp 顶部内边距」这个盒子，图标会比标题的视觉中心高出 12dp；同时 paddingEnd
     * 只作用于标题自己，图标会一路贴到卡片右缘。所以把**纵向和右侧**内边距上移到容器：
     * 容器的内容区正好剩下文字本身，居中才对得上，右侧栏距也才由容器统一决定。
     *
     * 图标排成一行贴着容器右缘，所以容器只留「24dp 栏距 − 图标自身 8dp 内衬」的右边距；
     * 标题再加一份「图标总宽」的右内边距，文字因此永远停在图标左边，不会钻到下面。
     */
    private fun wrapTitleWithActions(title: View, context: Context): View {
        val density = context.resources.displayMetrics.density
        val graphic = (WitDialogMetrics.TITLE_ACTION_GRAPHIC_DP * density).roundToInt()
        val inner = (WitDialogMetrics.TITLE_ACTION_PADDING_DP * density).roundToInt()
        val box = graphic + inner * 2

        val titleTop = title.paddingTop
        val titleEnd = title.paddingEnd
        title.setPadding(title.paddingStart, 0, mTitleActions.size * box, title.paddingBottom)

        val container = FrameLayout(context)
        // 右内边距扣掉图标自身的 8dp 内衬，让 24dp 图形的右缘落在跟标题左缘同一条 24dp 栏距上
        // （对齐的是图形，不是 40dp 的点击热区）。
        container.setPadding(0, titleTop, (titleEnd - inner).coerceAtLeast(0), 0)

        container.addView(
            title,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ),
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mTitleActions.forEach { action ->
            row.addView(actionIcon(action, context, box, inner))
        }
        container.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        return container
    }

    private fun actionIcon(
        action: TitleAction,
        context: Context,
        box: Int,
        inner: Int,
    ): AppCompatImageView = AppCompatImageView(context).apply {
        setImageResource(action.iconRes)
        imageTintList = ColorStateList.valueOf(palette.textAccent)
        contentDescription = action.contentDescription
        setPadding(inner, inner, inner, inner)
        setOnClickListener(action.listener)
        layoutParams = LinearLayout.LayoutParams(box, box)
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
