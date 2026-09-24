package ceui.pixiv.ui.navigation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font

/**
 * 平板首页的侧边导航栏（issue #1087，对应 docs/tablet-design 画廊原型的 88dp rail）。
 *
 * 窗口够宽时由 MainActivity 用它替换底栏：顶部是侧边菜单入口，中间是与底栏同一组 tab，
 * 分割线下是「收藏 / 下载」两个快捷入口（点了直接跳页，不参与选中）。
 *
 * 不用 Material 的 NavigationRailView：它的默认样式强制要求 MaterialComponents / Material3
 * 主题，本 App 的 AppTheme 是 AppCompat。这里按 V3 规范自己画——选中项是主题色浅底 +
 * 对比度校正过的强调色文字（[V3Palette.alpha15] / [V3Palette.textAccent]），其余是次级文字色。
 * 条目多于窗口高度时（横屏手机、分屏）整列可滚动。
 */
class HomeNavigationRail @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** tab 点击；[reselected] = 点的是当前已选中的 tab（宿主用来回顶 / 刷新）。 */
    fun interface DestinationListener {
        fun onDestinationClick(@IdRes id: Int, reselected: Boolean)
    }

    fun interface ShortcutListener {
        fun onShortcutClick(@IdRes id: Int)
    }

    private val palette = V3Palette.from(context)
    private val idleColor = context.color(R.color.v3_text_2)
    private val column = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val menuButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_baseline_dns_24)
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(context.color(R.color.v3_text_1)))
        background = context.ripple(null, shape(context.dpF(24f), Color.WHITE))
        contentDescription = context.getString(R.string.rail_open_menu)
    }
    private val destinations = LinearLayout(context).apply { orientation = VERTICAL }
    private val shortcuts = LinearLayout(context).apply { orientation = VERTICAL }
    private val shortcutDivider = View(context).apply {
        setBackgroundColor(context.color(R.color.v3_border_2))
    }
    private val items = LinkedHashMap<Int, Pair<ImageView, TextView>>()
    private var selectedId = View.NO_ID
    private var destinationListener: DestinationListener? = null
    private var shortcutListener: ShortcutListener? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(context.color(R.color.v3_bg))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        // 与内容区之间的 1px 细线（原型 border-inline-end）
        addView(View(context).apply {
            setBackgroundColor(context.color(R.color.v3_border_2))
        }, LayoutParams(context.hairlinePx(), LayoutParams.MATCH_PARENT))

        column.addView(menuButton, LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
            bottomMargin = context.dp(24)
        })
        column.addView(destinations, LinearLayout.LayoutParams(context.dp(ITEM_WIDTH_DP), LayoutParams.WRAP_CONTENT))
        column.addView(shortcutDivider, LinearLayout.LayoutParams(context.dp(44), context.hairlinePx()).apply {
            topMargin = context.dp(12)
            bottomMargin = context.dp(20)
        })
        column.addView(shortcuts, LinearLayout.LayoutParams(context.dp(ITEM_WIDTH_DP), LayoutParams.WRAP_CONTENT))
        shortcutDivider.visibility = GONE

        // 状态栏 / 导航栏 / 挖孔：rail 贴着窗口起始边，替整个首页吃掉那一侧的系统 inset。
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val startInset = if (layoutDirection == LAYOUT_DIRECTION_RTL) bars.right else bars.left
            v.setPaddingRelative(
                context.dp(10) + startInset,
                bars.top + context.dp(16),
                context.dp(10),
                bars.bottom + context.dp(16),
            )
            insets
        }
    }

    fun setOnMenuClickListener(listener: OnClickListener?) {
        menuButton.setOnClickListener(listener)
    }

    fun setDestinationListener(listener: DestinationListener?) {
        destinationListener = listener
    }

    fun setShortcutListener(listener: ShortcutListener?) {
        shortcutListener = listener
    }

    fun addDestination(@IdRes id: Int, @StringRes title: Int, @DrawableRes icon: Int) {
        destinations.addView(newItem(id, title, icon) {
            val reselected = id == selectedId
            setSelectedItemId(id)
            destinationListener?.onDestinationClick(id, reselected)
        })
    }

    fun addShortcut(@IdRes id: Int, @StringRes title: Int, @DrawableRes icon: Int) {
        shortcutDivider.visibility = VISIBLE
        shortcuts.addView(newItem(id, title, icon) { shortcutListener?.onShortcutClick(id) })
    }

    fun setSelectedItemId(@IdRes id: Int) {
        if (id == selectedId) return
        selectedId = id
        items.forEach { (itemId, views) -> render(itemId, views.first, views.second) }
    }

    private fun newItem(
        @IdRes id: Int,
        @StringRes title: Int,
        @DrawableRes icon: Int,
        onClick: () -> Unit,
    ): View {
        val ctx = context
        val iconView = ImageView(ctx).apply { setImageResource(icon) }
        val labelView = ctx.label(ctx.getString(title), 12f, 500, idleColor).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val item = LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = ctx.dp(64)
            setPadding(ctx.dp(4), ctx.dp(10), ctx.dp(4), ctx.dp(10))
            isClickable = true
            isFocusable = true
            addView(iconView, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
            addView(labelView, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(6) })
            setOnClickListener { onClick() }
            pressScale()
        }
        items[id] = iconView to labelView
        render(id, iconView, labelView)
        return item.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = ctx.dp(8) }
        }
    }

    private fun render(@IdRes id: Int, iconView: ImageView, labelView: TextView) {
        val selected = id == selectedId
        val fg = if (selected) palette.textAccent else idleColor
        ImageViewCompat.setImageTintList(iconView, ColorStateList.valueOf(fg))
        labelView.setTextColor(fg)
        labelView.typeface = context.v3Font(if (selected) 600 else 500)
        val item = iconView.parent as? View ?: return
        item.isSelected = selected
        val radius = context.dpF(ITEM_RADIUS_DP)
        item.background = context.ripple(
            shape(radius, if (selected) palette.alpha15 else Color.TRANSPARENT),
            shape(radius, Color.WHITE),
        )
    }

    private companion object {
        const val ITEM_WIDTH_DP = 68
        const val ITEM_RADIUS_DP = 22f
    }
}
