package ceui.lisa.update

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock

/**
 * 版本历史与更新弹窗共用的一份 Markwon 配置 —— GitHub 的 release body 是 Markdown，
 * 两处渲染的必须是同一种排版。
 *
 * 标题只比正文大一点（更新说明里的 `##` 不该长成页面标题），链接走对比度已校正的
 * [V3Palette.textAccent]；正文颜色、字号、行高归调用方的 TextView，这里不碰。
 *
 * 代码块和行内代码换掉了 Markwon 自带的 span：默认那套把整段刷成一块直角的
 * `codeBlockBackgroundColor`，压在 22dp 卡片里就是一条硬邦邦的灰板（v4.7.9 那条带
 * SHA 校验值的更新说明最明显）。这里改成 [CodeBlockSurfaceSpan]：12dp 圆角、左右留白、
 * 底色是卡底再混一层 8% 主题色（同广场的评论预览）；行内代码干脆不要底，只用等宽加
 * 强调色——一行字里嵌一块小灰方格同样难看。
 */
internal fun markwonFor(context: Context): Markwon {
    val palette = V3Palette.from(context)
    // 代码块坐在卡片里，底色从卡底派生，不用通用的中性灰。
    val codeSurface = ColorUtils.compositeColors(palette.alpha08, palette.cardFill)
    val codeInk = context.color(R.color.v3_text_1)
    val radius = context.dpF(12f)
    val padH = context.dpF(12f)
    val padV = context.dpF(6f)
    return Markwon.builder(context)
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.headingTextSizeMultipliers(floatArrayOf(1.15f, 1.1f, 1.05f, 1f, .95f, .9f))
                builder.headingBreakHeight(0)
                builder.linkColor(palette.textAccent)
                builder.bulletWidth(context.dp(4))
                builder.blockQuoteColor(palette.alpha30)
            }

            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                // 每次都要新的实例：这几个 span 都带位置状态，不能跨段复用。
                fun codeBlock(): Array<Any> = arrayOf(
                    CodeBlockSurfaceSpan(codeSurface, radius, padH, padV),
                    TypefaceSpan("monospace"),
                    RelativeSizeSpan(.92f),
                    ForegroundColorSpan(codeInk),
                )
                builder.setFactory(FencedCodeBlock::class.java) { _, _ -> codeBlock() }
                builder.setFactory(IndentedCodeBlock::class.java) { _, _ -> codeBlock() }
                builder.setFactory(Code::class.java) { _, _ ->
                    arrayOf(TypefaceSpan("monospace"), ForegroundColorSpan(palette.textAccent))
                }
            }
        })
        .build()
}

/**
 * 代码块的圆角底。
 *
 * Markwon 的 `MarkwonTheme` 只给得了一个背景**颜色**，画出来永远是贴着文字边的直角色块；
 * 要圆角就得自己接管这一段的 span。[LineBackgroundSpan] 是逐行回调的，所以首行和末行
 * 各自只圆自己那两个角、中间行画直角，拼起来才是一整块圆角面；上下各多画 [padV]，
 * 让文字不贴着边（正文 TextView 留了同量的上下 padding，最后一块不会被裁掉）。
 */
private class CodeBlockSurfaceSpan(
    @ColorInt fill: Int,
    private val radius: Float,
    private val padH: Float,
    private val padV: Float,
) : LineBackgroundSpan, LeadingMarginSpan {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val rect = RectF()
    private val path = Path()
    private val radii = FloatArray(8)

    override fun getLeadingMargin(first: Boolean): Int = padH.toInt()

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout?,
    ) = Unit

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val spanned = text as? Spanned
        val isFirst = spanned == null || start <= spanned.getSpanStart(this)
        val isLast = spanned == null || end >= spanned.getSpanEnd(this)
        rect.set(
            left.toFloat(),
            top.toFloat() - if (isFirst) padV else 0f,
            right.toFloat(),
            bottom.toFloat() + if (isLast) padV else 0f,
        )
        radii.fill(0f)
        if (isFirst) {
            radii[0] = radius; radii[1] = radius
            radii[2] = radius; radii[3] = radius
        }
        if (isLast) {
            radii[4] = radius; radii[5] = radius
            radii[6] = radius; radii[7] = radius
        }
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(path, fillPaint)
    }
}
