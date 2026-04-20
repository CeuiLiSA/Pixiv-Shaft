package ceui.pixiv.ui.novel.reader.paginate

import android.os.Build
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
        val builder = StaticLayout.Builder.obtain(source, 0, source.length, paint, usableWidth)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // StaticLayout.Builder defaults this to false, but TextView defaults
            // to true since API 28. When the novel text pushes a glyph through a
            // fallback font (common for CJK / emoji / latin in JP text), that
            // line's metrics expand under `true` and stay at the base font's
            // under `false`. The mismatch meant every fallback-touching line
            // was a few pixels taller in the TextView than in the paginator's
            // budget — fine for one line, screen-scrolling overflow across a
            // full page. Align both sides on `true`.
            builder.setUseLineSpacingFromFallbacks(true)
        }
        return builder.build()
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
