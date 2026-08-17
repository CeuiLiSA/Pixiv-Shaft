package ceui.pixiv.witstudio.dialog

import android.content.Context
import android.util.AttributeSet
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitDisplay

/**
 * 弹窗的卡片本体，替代 `QMUIDialogView`。
 *
 * QMUI 那个是 `ConstraintLayout`，靠 topToBottom / bottomToTop 把标题—内容—按钮串成一条链。
 * 这里换成竖向 [LinearLayout]，因为模块刻意不引 constraintlayout，而 LinearLayout 的
 * 权重回收正好给出想要的行为：内容区 `height=wrap_content` + `weight=1` 时，
 * 总高没超过上限就按内容高（delta=0，不拉伸），超了才把负 delta 只摊给内容区
 * —— 也就是「标题和按钮永远可见，只有中间滚动」。
 *
 * 底色走 [V3Palette.settingsCardBg]：运行时从 `?attr/colorPrimary` 派生，
 * 所以十档主题预设和自定义 HEX 都能跟上，而且 phase 6 主题重挂时这里零改动。
 */
public class WitDialogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    init {
        orientation = VERTICAL
        background = V3Palette.from(context).settingsCardBg(
            WitDisplay.dp2pxF(context, WitDialogMetrics.RADIUS_DP),
            WitDisplay.hairline(context),
        )
        // 菜单行的 ripple 是整行铺满的，不裁就会溢出到卡片圆角外面。
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }
}
