package ceui.pixiv.ui.novel.reader.paginate

import android.content.Context
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import ceui.pixiv.ui.novel.reader.model.ImagePlacement
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme

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
    val chapterTopGapPx: Float,
    val chapterBottomGapPx: Float,
) {
    val textSize: Float get() = textPaint.textSize

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
            val fontHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            val paragraphSpacing = fontHeight * settings.paragraphSpacingLines
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
                chapterTopGapPx = chapterHeight * 0.8f,
                chapterBottomGapPx = chapterHeight * 1.2f,
            )
        }
    }
}
