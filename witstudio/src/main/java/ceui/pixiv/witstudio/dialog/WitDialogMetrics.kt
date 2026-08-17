package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.annotation.ColorInt
import ceui.pixiv.witstudio.theme.WitDisplay

/**
 * 弹窗的排版常量。
 *
 * 刻意全部写成 dp/sp 字面量而不是 `?attr/textAppearance*` —— 后者在 phase 6（主题重挂 M3）
 * 前后解析出的值不一样，会让弹窗在迁移途中悄悄改变字号。这里的数字在整个迁移过程中恒定。
 *
 * 数值参照 QMUI 原值再按 V3/MD3-E 调整：圆角 12→28（Expressive）、
 * 标题 17→19sp、正文 16→15sp、按钮容器加高（44dp，够到 MD3 的可点区下限）。
 */
internal object WitDialogMetrics {

    /** 卡片圆角。MD3-E 的大圆角档。 */
    const val RADIUS_DP: Float = 28f

    /** 弹窗左右各留的屏幕边距，和最大宽度一起决定实际宽。 */
    const val INSET_HORIZONTAL_DP: Int = 32
    const val MAX_WIDTH_DP: Int = 340

    /** 弹窗最高占屏比。超出后内容区滚动，标题和按钮恒定可见。 */
    const val MAX_HEIGHT_PERCENT: Float = 0.85f

    /** 标题 / 正文 / 输入框共用的左右内边距。 */
    const val PADDING_HORIZONTAL_DP: Int = 24

    const val TITLE_TEXT_SP: Float = 19f
    const val TITLE_PADDING_TOP_DP: Int = 24

    /** 只有标题、没有内容区时，标题下方要自己补出内容区本该有的留白。 */
    const val TITLE_PADDING_BOTTOM_WHEN_NO_CONTENT_DP: Int = 24

    const val MESSAGE_TEXT_SP: Float = 15f
    const val MESSAGE_LINE_SPACING_DP: Float = 4f

    /** 正文顶部留白；无标题时要补得更多，否则文字贴着卡片上沿。 */
    const val MESSAGE_PADDING_TOP_DP: Int = 12
    const val MESSAGE_PADDING_TOP_WHEN_NO_TITLE_DP: Int = 24
    const val MESSAGE_PADDING_BOTTOM_DP: Int = 20

    const val ACTION_TEXT_SP: Float = 14f
    const val ACTION_HEIGHT_DP: Int = 44
    const val ACTION_MIN_WIDTH_DP: Int = 72
    const val ACTION_PADDING_HORIZONTAL_DP: Int = 16
    const val ACTION_SPACE_DP: Int = 4
    const val ACTION_ICON_SPACE_DP: Int = 6

    /** 按钮容器四周的内边距，和卡片圆角对齐，避免胶囊 ripple 咬到圆角外。 */
    const val ACTION_CONTAINER_PADDING_HORIZONTAL_DP: Int = 12
    const val ACTION_CONTAINER_PADDING_BOTTOM_DP: Int = 12

    const val MENU_ITEM_HEIGHT_DP: Int = 52
    const val MENU_ITEM_TEXT_SP: Float = 15f
    const val MENU_MARK_SIZE_DP: Int = 22
    const val MENU_MARK_SPACE_DP: Int = 12

    /** 菜单容器上下留白。有标题 / 有按钮时各自收窄，避免出现双份留白。 */
    const val MENU_CONTAINER_PADDING_VERTICAL_DP: Int = 12
    const val MENU_CONTAINER_PADDING_TOP_WHEN_TITLE_DP: Int = 4
    const val MENU_CONTAINER_PADDING_BOTTOM_WHEN_ACTION_DP: Int = 4

    const val EDIT_TEXT_SP: Float = 15f
    const val EDIT_MARGIN_TOP_DP: Int = 16
    const val EDIT_MARGIN_BOTTOM_DP: Int = 20
    const val EDIT_RADIUS_DP: Float = 14f
    const val EDIT_PADDING_HORIZONTAL_DP: Int = 14
    const val EDIT_MIN_HEIGHT_DP: Int = 48

    const val CHECKBOX_SIZE_DP: Int = 22
    const val CHECKBOX_SPACE_DP: Int = 12
}

/**
 * 胶囊形 ripple。内容层留空（透明），只有 mask 决定水波范围 —— 弹窗按钮是「文字按钮」，
 * 静止态不该有底色。
 */
internal fun pillRipple(context: Context, @ColorInt rippleColor: Int, radiusDp: Float): RippleDrawable {
    val mask = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = WitDisplay.dp2pxF(context, radiusDp)
        setColor(Color.WHITE)
    }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
}
