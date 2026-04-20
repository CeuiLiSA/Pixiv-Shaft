package ceui.pixiv.ui.novel.reader.paginate

import android.content.Context
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.roundToInt

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
        // TextView.setText() → checkForRelayout() reads `getLayoutParams().width`
        // and NPEs if LayoutParams is null. Detached measuring TextViews have
        // no parent to assign one, so give it a placeholder here. Our explicit
        // `measure(EXACTLY, ...)` call supplies the real width downstream.
        it.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
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
        // Fixed line-height is installed per-text via [FixedLineHeightSpan]
        // below, so clear setLineSpacing's mult/extra here — any leftover
        // multiplier would re-amplify the span's target height.
        tv.setLineSpacing(0f, 1f)
        tv.gravity = alignment.toGravity()
        tv.setText(wrapWithFixedLineHeight(text, paint, lineSpacingMultiplier, lineSpacingExtra), TextView.BufferType.SPANNABLE)
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

        /**
         * Wrap [text] with a [FixedLineHeightSpan] covering every character,
         * so every rendered line gets the exact same pixel height regardless
         * of paint metrics, fallback fonts, or StaticLayout's last-line
         * shortcut.
         *
         * Why not `setLineSpacing(extra, mult)` / `setLineHeight`: Android's
         * StaticLayout skips `mult + extra` on the *last line of each
         * paragraph*. The paginator measures each paragraph in isolation
         * (every last line = natural metrics), the renderer merges all
         * paragraphs (only the document's very last line misses it). The
         * per-paragraph drift of `fontHeight × (mult - 1)` accumulates into
         * hundreds of pixels over a page — content ended up clipped at the
         * bottom of the fixed-size text block.
         *
         * `LineHeightSpan.chooseHeight` fires before the add/mult step, and
         * our absolute `fm.ascent/descent` overwrite means the natural line
         * height IS the target — the skipped multiplier is multiplicative
         * identity (1.0) with our setLineSpacing(0, 1) in the TextView.
         */
        fun wrapWithFixedLineHeight(
            text: CharSequence,
            paint: TextPaint,
            lineSpacingMultiplier: Float,
            lineSpacingExtra: Float,
        ): Spannable {
            val fm = paint.fontMetrics
            val natural = (fm.descent - fm.ascent).coerceAtLeast(1f)
            val mult = lineSpacingMultiplier.coerceAtLeast(0.8f)
            val targetHeight = (natural * mult + lineSpacingExtra).roundToInt().coerceAtLeast(1)
            val spannable = when (text) {
                is SpannableStringBuilder -> text
                is Spannable -> text
                else -> SpannableString(text)
            }
            spannable.setSpan(
                FixedLineHeightSpan(targetHeight),
                0, spannable.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
            return spannable
        }
    }

    /**
     * Pins a line to an absolute pixel height by rewriting the
     * [Paint.FontMetricsInt] that StaticLayout uses. Idempotent so repeated
     * chooseHeight calls within the same paragraph don't accumulate.
     *
     * Copies the logic of [android.text.style.LineHeightSpan.Standard]
     * (which is only API 29+) so we can target API 24.
     */
    class FixedLineHeightSpan(private val height: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: Paint.FontMetricsInt,
        ) {
            val original = fm.descent - fm.ascent
            if (original <= 0) return
            val ratio = height.toFloat() / original
            fm.descent = (fm.descent * ratio).roundToInt()
            fm.ascent = fm.descent - height
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }
}

private fun Layout.Alignment.toGravity(): Int = when (this) {
    Layout.Alignment.ALIGN_CENTER -> Gravity.CENTER_HORIZONTAL
    Layout.Alignment.ALIGN_OPPOSITE -> Gravity.END
    else -> Gravity.START
}
