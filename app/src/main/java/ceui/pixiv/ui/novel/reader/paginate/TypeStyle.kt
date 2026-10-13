package ceui.pixiv.ui.novel.reader.paginate

import android.content.Context
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import ceui.pixiv.ui.novel.reader.model.ImagePlacement
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import kotlin.math.roundToInt

/**
 * Derived style for layout passes. Build a fresh one whenever settings or theme
 * change and hand it to [Paginator] / renderer. Paints are mutable under the hood
 * but the Paginator/renderer treat them as read-only to avoid thread drama.
 */
data class TypeStyle(
    val textPaint: TextPaint,
    val chapterPaint: TextPaint,
    val captionPaint: TextPaint,
    val lineSpacingMultiplier: Float,
    val lineSpacingExtra: Float,
    val paragraphSpacingPx: Float,
    val firstLineIndentPx: Float,
    val imagePlacement: ImagePlacement,
    val imageScaleMode: ImageScaleMode,
    val accentColor: Int,
    val selectionColor: Int,
    val highlightColor: Int,
    val backgroundColor: Int,
    val dividerColor: Int,
    val secondaryTextColor: Int,
    val linkColor: Int,
    val chapterTopGapPx: Float,
    val chapterBottomGapPx: Float,
    /** 全文翻译的译文：字号降一级、次要文字色（issue #864）。字号取整像素，见 [from]。 */
    val translationPaint: TextPaint,
    /** 原文段落与紧随其后的译文之间的间距，比段间距紧，让一对原文/译文读起来是一组。 */
    val translationGapPx: Float,
) {
    val textSize: Float get() = textPaint.textSize
    val textLineHeightPx: Int
        get() = TextMeasurer.lineHeightPx(textPaint, lineSpacingMultiplier, lineSpacingExtra)
    val translationLineHeightPx: Int
        get() = TextMeasurer.lineHeightPx(translationPaint, lineSpacingMultiplier, lineSpacingExtra)

    companion object {
        fun from(context: Context, settings: ReaderSettings.Snapshot, theme: ReaderTheme): TypeStyle {
            val dm = context.resources.displayMetrics
            val typeface = TypefaceProvider.resolve(
                context = context,
                fontId = settings.fontId,
                weight = settings.fontWeight,
                bold = settings.boldText,
            )
            val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                settings.fontSizeSp.toFloat(),
                dm,
            )

            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                this.textSize = textSizePx
                this.color = theme.textColor
                this.letterSpacing = settings.letterSpacing
                this.isFakeBoldText = settings.boldText && settings.fontWeight < 500
            }
            val chapterPaint = TextPaint(textPaint).apply {
                this.textSize = textSizePx * 1.55f
                this.color = theme.chapterTitleColor
                this.isFakeBoldText = true
                this.letterSpacing = 0f
            }
            val captionPaint = TextPaint(textPaint).apply {
                this.textSize = textSizePx * 0.82f
                this.color = theme.secondaryTextColor
                this.isFakeBoldText = false
            }
            // 译文在翻页模式下与原文合进同一个 TextView，靠 AbsoluteSizeSpan(Int) 换字号；
            // 分页测量走 setTextSize(PX, Float)。字号取整像素，两条路径才逐像素一致。
            val translationPaint = TextPaint(textPaint).apply {
                this.textSize = (textSizePx * 0.9f).roundToInt().toFloat()
                this.color = theme.secondaryTextColor
            }
            // "1 line" means one rendered body line, including the user's line spacing.
            // Round once here so pagination and every renderer budget the same pixels.
            val paragraphSpacing = (TextMeasurer.lineHeightPx(textPaint, settings.lineSpacing)
                * settings.paragraphSpacingLines).roundToInt().toFloat()
            val indentPx = textSizePx * settings.firstLineIndent
            val chapterHeight = chapterPaint.fontMetrics.bottom - chapterPaint.fontMetrics.top
            return TypeStyle(
                textPaint = textPaint,
                chapterPaint = chapterPaint,
                captionPaint = captionPaint,
                lineSpacingMultiplier = settings.lineSpacing,
                lineSpacingExtra = 0f,
                paragraphSpacingPx = paragraphSpacing,
                firstLineIndentPx = indentPx,
                imagePlacement = settings.imagePlacement,
                imageScaleMode = settings.imageScaleMode,
                accentColor = theme.accentColor,
                selectionColor = theme.selectionColor,
                highlightColor = theme.highlightColor,
                backgroundColor = theme.backgroundColor,
                dividerColor = theme.dividerColor,
                secondaryTextColor = theme.secondaryTextColor,
                linkColor = theme.linkColor,
                chapterTopGapPx = chapterHeight * 0.8f,
                chapterBottomGapPx = chapterHeight * 1.2f,
                translationPaint = translationPaint,
                translationGapPx = (paragraphSpacing * 0.35f).roundToInt().toFloat(),
            )
        }
    }
}
