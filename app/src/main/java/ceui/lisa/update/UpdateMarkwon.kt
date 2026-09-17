package ceui.lisa.update

import android.content.Context
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme

/**
 * 版本历史与更新弹窗共用的一份 Markwon 配置 —— GitHub 的 release body 是 Markdown，
 * 两处渲染的必须是同一种排版。
 *
 * 标题只比正文大一点（更新说明里的 `##` 不该长成页面标题），链接和行内代码走
 * 对比度已校正的 [V3Palette.textAccent]，代码块坐在中性的 `v3_surface_2` 上；
 * 正文颜色、字号、行高归调用方的 TextView，这里不碰。
 */
internal fun markwonFor(context: Context): Markwon {
    val palette = V3Palette.from(context)
    return Markwon.builder(context)
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.headingTextSizeMultipliers(floatArrayOf(1.15f, 1.1f, 1.05f, 1f, .95f, .9f))
                builder.headingBreakHeight(0)
                builder.linkColor(palette.textAccent)
                builder.bulletWidth(context.dp(4))
                builder.blockQuoteColor(palette.alpha30)
                builder.codeTextColor(palette.textAccent)
                builder.codeBackgroundColor(context.color(R.color.v3_surface_2))
                builder.codeBlockTextColor(context.color(R.color.v3_text_1))
                builder.codeBlockBackgroundColor(context.color(R.color.v3_surface_2))
            }
        })
        .build()
}
