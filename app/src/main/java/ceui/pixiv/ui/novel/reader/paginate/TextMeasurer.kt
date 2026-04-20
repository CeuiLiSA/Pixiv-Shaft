package ceui.pixiv.ui.novel.reader.paginate

import android.content.Context
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView

/**
 * Produces a [Layout] for pagination via the exact same path the reader uses
 * to render — an [AppCompatTextView] configured by [applyLayoutSettings].
 *
 * Why this class (and not a bare [StaticLayout.Builder]): TextView's internal
 * StaticLayout differs from one we build by hand in several subtle defaults
 * (`fallbackLineSpacing`, text-direction resolution, density-scaled paint,
 * justification mode) that we can't feasibly keep in sync across future
 * Android versions. Running measurement through the same `AppCompatTextView`
 * class we render with makes drift impossible by construction.
 *
 * The internal measuring TextView is **reused** across calls — its internal
 * Layout object is replaced each `measure()`. The caller must consume the
 * returned Layout's data (line starts/ends/top/bottom and the sliced text)
 * before calling [measure] again on the same instance.
 *
 * Must be constructed and called from the main thread (TextView construction
 * requires a Looper; `setText` / `measure` interact with handlers). The
 * reader pagination pipeline runs on Main for that reason.
 */
class TextMeasurer(context: Context) {

    private val measurer: AppCompatTextView = AppCompatTextView(context).also {
        applyLayoutSettings(it)
    }

    fun measure(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        lineSpacingMultiplier: Float,
        lineSpacingExtra: Float,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): Layout {
        val tv = measurer
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, paint.textSize)
        tv.typeface = paint.typeface
        tv.setTextColor(paint.color)
        tv.letterSpacing = paint.letterSpacing
        tv.setLineSpacing(lineSpacingExtra, lineSpacingMultiplier.coerceAtLeast(0.8f))
        tv.gravity = alignment.toGravity()
        tv.setText(text, TextView.BufferType.SPANNABLE)
        val usableWidth = width.coerceAtLeast(1)
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(usableWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return tv.layout
    }

    companion object {
        /**
         * StaticLayout-based measurement. Used by non-TextView rendering paths
         * (currently PdfExporter, which draws text straight to a Canvas). The
         * reader itself must NOT use this — rendering through an
         * AppCompatTextView would diverge in line metrics.
         */
        fun buildStaticLayout(
            text: CharSequence,
            paint: TextPaint,
            width: Int,
            lineSpacingMultiplier: Float,
            lineSpacingExtra: Float,
            alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        ): StaticLayout {
            val usableWidth = width.coerceAtLeast(1)
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, usableWidth)
                .setAlignment(alignment)
                .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier.coerceAtLeast(0.8f))
                .setIncludePad(false)
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

        /**
         * Layout settings shared between the measurer and the reader's
         * rendering [ceui.pixiv.ui.novel.reader.render.ReaderTextBlockView].
         * Any new layout-affecting setting MUST be added here (not ad-hoc on
         * one side), otherwise pagination and rendering will silently drift.
         *
         * Settings that affect visuals but not layout (highlight color, text
         * color, background, selection chrome) intentionally live on the
         * call site.
         */
        fun applyLayoutSettings(tv: TextView) {
            tv.setPadding(0, 0, 0, 0)
            tv.includeFontPadding = false
            tv.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            tv.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // TextView defaults this to true on API 28+, which expands
                // every line that touches a fallback-font glyph (CJK through
                // a latin-primary typeface, emoji, etc.) — enough to push
                // the paginator's budget out of sync with the render over a
                // full page. Force false on both sides.
                tv.isFallbackLineSpacing = false
            }
        }
    }
}

private fun Layout.Alignment.toGravity(): Int = when (this) {
    Layout.Alignment.ALIGN_CENTER -> Gravity.CENTER_HORIZONTAL
    Layout.Alignment.ALIGN_OPPOSITE -> Gravity.END
    else -> Gravity.START
}
