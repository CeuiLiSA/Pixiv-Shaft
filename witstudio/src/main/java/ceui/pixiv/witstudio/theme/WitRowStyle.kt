package ceui.pixiv.witstudio.theme

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import ceui.pixiv.witstudio.R

/**
 * MD3-E 分段行（segmented rows）—— V3 列表的招牌语汇。
 *
 * 一组行共用一块视觉容器：首行上圆下方、中间行四角小圆、末行上方下圆，行间留 2dp 缝。
 * 外角 20dp / 内角 5dp 的对比是「分段」读感的来源，缝隙靠 `layout_marginTop="2dp"` 拉开。
 *
 * 典型用法：
 * ```
 * row.setBackgroundResource(WitRowStyle.rowBackground(index, total))
 * // …整组行都 inflate 完之后，对根 view 调一次：
 * WitRowStyle.applyThemedRowBg(root)
 * ```
 */
public object WitRowStyle {

    /**
     * 按行在组内的位置选背景：独行 / 段首 / 段中 / 段尾。
     *
     * 这段逻辑此前在 `AccountSwitchV3Fragment`、`FlagReasonFragment`、`FragmentSettingsHub`
     * 里各抄了一份，收到这里统一。注意边界：[total] 为 1 时是独行（四角都圆），
     * 而不是「既是首行又是末行」。
     */
    @JvmStatic
    @DrawableRes
    public fun rowBackground(index: Int, total: Int): Int = when {
        total <= 1 -> R.drawable.wit_row_single
        index == 0 -> R.drawable.wit_row_top
        index == total - 1 -> R.drawable.wit_row_bottom
        else -> R.drawable.wit_row_mid
    }

    /**
     * 把分段行的中性底色换成 [V3Palette.cardFill] 的主题 tint。
     *
     * `wit_row_*` 是 ripple 包 GradientDrawable，这里原地 mutate 换 fill + hairline，
     * 圆角与 ripple 保持不变；递归处理整棵子树，行以外的背景不受影响。
     *
     * ⚠️ 判定条件依赖 drawable 的结构（外层 RippleDrawable、layer 0 是 GradientDrawable）。
     * 结构不符时**静默跳过**，不会抛异常 —— 所以改 `wit_row_*` 的结构后必须肉眼验证
     * 主题色是否还跟随，编译器和测试都拦不住。
     */
    @JvmStatic
    public fun applyThemedRowBg(view: View) {
        val palette = V3Palette.from(view.context)
        val strokePx = (0.5f * view.resources.displayMetrics.density).coerceAtLeast(1f).toInt()
        tintRowsRecursively(view, palette, strokePx)
    }

    private fun tintRowsRecursively(v: View, palette: V3Palette, strokePx: Int) {
        val bg = v.background
        if (bg is RippleDrawable && bg.numberOfLayers > 0 && bg.getDrawable(0) is GradientDrawable) {
            bg.mutate()
            (bg.getDrawable(0) as GradientDrawable).apply {
                setColor(palette.cardFill)
                setStroke(strokePx, palette.cardHairline)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                tintRowsRecursively(v.getChildAt(i), palette, strokePx)
            }
        }
    }
}
