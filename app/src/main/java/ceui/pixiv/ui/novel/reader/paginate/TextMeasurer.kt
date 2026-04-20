package ceui.pixiv.ui.novel.reader.paginate

import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan

object TextMeasurer {
    fun buildLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        lineSpacingMultiplier: Float,
        lineSpacingExtra: Float,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout {
        val source = text
        val usableWidth = width.coerceAtLeast(1)
        // NOTE on fallback line spacing: StaticLayout.Builder defaults
        // `useLineSpacingFromFallbacks` to false and TextView defaults
        // `fallbackLineSpacing` to true (API 28+). We intentionally keep the
        // StaticLayout default and force TextView back to false in
        // [ceui.pixiv.ui.novel.reader.render.ReaderTextBlockView] so both
        // sides measure with base font metrics — otherwise CJK glyphs running
        // through a fallback font make every line a few pixels taller in the
        // TextView than in the paginator's budget, which used to overflow the
        // page (now clipped by the explicit block height). Forcing base
        // metrics also preserves the user's configured line-spacing visual.
        return StaticLayout.Builder.obtain(source, 0, source.length, paint, usableWidth)
            .setAlignment(alignment)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier.coerceAtLeast(0.8f))
            .setIncludePad(false)
            // SIMPLE is greedy per line — breaking a substring with the same
            // paint / width reproduces the original breaks exactly. HIGH_QUALITY
            // optimises across all lines jointly, so slicing from line N onward
            // (as the TextView rendering path does for mid-paragraph pages) can
            // land on different break positions and a different line count,
            // which leaks into page overflow.
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    fun withFirstLineIndent(text: String, indentPx: Int): CharSequence {
        if (indentPx <= 0 || text.isEmpty()) return text
        val spannable = SpannableString(text)
        spannable.setSpan(
            LeadingMarginSpan.Standard(indentPx, 0),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return spannable
    }
}
