package ceui.pixiv.ui.novel.reader.render

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import ceui.pixiv.ui.novel.reader.model.PageElement
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import kotlin.math.roundToInt

/**
 * A selectable text block hosting a run of [PageElement.Text] slices. Uses
 * the platform's native selection (handles, magnifier, action mode) — we
 * inject a custom menu via [setCustomSelectionActionModeCallback] and
 * translate the TextView's local offsets back to source-absolute offsets
 * through [absoluteCharStart].
 *
 * Pagination and rendering share a layout pipeline. The paginator's
 * [TextMeasurer] drives an AppCompatTextView with the exact same settings
 * this view uses (see [TextMeasurer.applyLayoutSettings]), so the line
 * breaks the paginator saw for this slice are reproduced here verbatim.
 * Line count, line heights and widths match by construction.
 *
 * Sized by the paginator via explicit width/height on its LayoutParams
 * (see [ceui.pixiv.ui.novel.reader.render.PageView.rebuildTextBlocks]) —
 * a page is a fixed rect, not a scrollable region. The scrollTo /
 * scrollBy / canScrollVertically overrides below close the final escape
 * hatch in TextView's `textIsSelectable=true` path (ArrowKeyMovementMethod,
 * Editor bringPointIntoView) so the block can't accidentally become
 * internally scrollable even if measurement drift ever returns.
 */
class ReaderTextBlockView(context: Context) : AppCompatTextView(context) {

    /**
     * Local-char range → source-absolute-char mapping. Each segment is the
     * contiguous region of this view's text produced by one [PageElement.Text]
     * — gaps between segments correspond to the paragraph separator chars
     * (`\n\n`) we insert when merging multiple paragraphs into a single view.
     */
    private data class Segment(val localStart: Int, val localEnd: Int, val absoluteStart: Int)
    private val segments = mutableListOf<Segment>()

    /** Hook fired when the ActionMode is created for a fresh selection so the
     *  host can populate `activeSelection` before the user taps a menu item. */
    var onSelectionStarted: ((block: ReaderTextBlockView, absStart: Int, absEnd: Int, text: CharSequence) -> Unit)? = null
    var onSelectionChanged: ((block: ReaderTextBlockView, absStart: Int, absEnd: Int, text: CharSequence) -> Unit)? = null
    var onSelectionEnded: ((block: ReaderTextBlockView) -> Unit)? = null

    /** Menu definition (title + id), populated by the host. Action dispatch flows
     *  back through [onMenuAction]. */
    data class MenuEntry(val id: Int, val title: CharSequence)

    var menuEntries: List<MenuEntry> = emptyList()
    var onMenuAction: ((id: Int) -> Unit)? = null

    /**
     * Fired when a short tap that didn't produce (or dismiss) a selection
     * lands on this text block. Coordinates are in this view's own space;
     * host is expected to translate to its own frame via [View.getX]/[View.getY].
     */
    var onBareTap: ((xInBlock: Float, yInBlock: Float) -> Unit)? = null

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L

    init {
        // Layout-affecting settings (padding, break strategy, hyphenation,
        // fallback line spacing, include-font-padding) are applied through the
        // SAME helper that configures the paginator's measuring TextView. Any
        // drift here becomes a pagination/render mismatch, so keep them
        // together in [TextMeasurer.applyLayoutSettings].
        TextMeasurer.applyLayoutSettings(this)
        setTextIsSelectable(true)
        // setTextIsSelectable(true) → focusable, long-clickable, and an
        // ArrowKeyMovementMethod installed — we want all that. What we don't
        // want is the default link/ripple highlight on selection.
        highlightColor = 0x665B6EFF
        setBackgroundColor(Color.TRANSPARENT)
        customSelectionActionModeCallback = buildActionModeCallback()
    }

    /**
     * Bind a contiguous run of text elements (possibly from multiple
     * paragraphs) into a single TextView so the native selection can drag
     * across paragraph boundaries.
     *
     * Between consecutive paragraphs we insert a single `\n` and stretch the
     * descent of the line containing it via [ParagraphGapLineHeightSpan] to
     * match [TypeStyle.paragraphSpacingPx] — inserting `\n\n` would add a
     * whole extra row per boundary. First-line indent is reapplied via
     * [LeadingMarginSpan.Standard] against the sliced range (the original
     * layout's LeadingMarginSpan is lost when we call `.toString()`).
     * Line-break positions survive the merge because we preserve paint, width,
     * line-spacing and the greedy break strategy used by the paginator.
     */
    fun bindTextGroup(elements: List<PageElement.Text>, style: TypeStyle) {
        segments.clear()
        val sb = SpannableStringBuilder()
        elements.forEachIndexed { idx, element ->
            val rawSlice = element.text.toString().trimEnd('\n')

            val segLocalStart = sb.length
            sb.append(rawSlice)
            val segLocalEnd = sb.length
            segments += Segment(
                localStart = segLocalStart,
                localEnd = segLocalEnd,
                absoluteStart = element.absoluteCharStart,
            )

            if (element.isFirstLineOfParagraph && style.firstLineIndentPx > 0f) {
                sb.setSpan(
                    LeadingMarginSpan.Standard(style.firstLineIndentPx.toInt(), 0),
                    segLocalStart, segLocalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            if (idx < elements.size - 1) {
                // One `\n` so the next paragraph starts on a fresh line — NOT
                // two, which would add a blank line per boundary and push the
                // content past the page height. To preserve the author-
                // configured paragraph gap, stretch the descent of the line
                // containing the `\n` via a LineHeightSpan.
                //
                // Use the *actual* pixels the paginator reserved between
                // these two text elements (nextElement.top - element.bottom)
                // rather than a hardcoded `style.paragraphSpacingPx`. The
                // paginator always adds paragraphSpacingPx after a completed
                // paragraph, AND if the source had an explicit blank line
                // between them, a PageElement.Space on top with its own gap
                // (`max(paragraphSpacingPx, fontHeight)`). Grouping is
                // transparent to Space, so both paragraphs land in the same
                // TextView — if we only inject paragraphSpacingPx here, the
                // TextView content ends short of the rect the paginator
                // budgeted for this group and the remainder shows up as
                // visible empty space at page bottom / between paragraphs.
                //
                // Compensate for StaticLayout's lineSpacingMultiplier: it
                // multiplies the font-metrics height per-line, so whatever we
                // add to descent is amplified by `multiplier`. Divide to land
                // on the exact pixel gap the paginator uses; round instead of
                // truncating so accumulated drift stays sub-pixel.
                val pixelGap = (elements[idx + 1].top - element.bottom).coerceAtLeast(0f)
                val mult = style.lineSpacingMultiplier.coerceAtLeast(0.1f)
                val gap = (pixelGap / mult).roundToInt()
                val sepStart = sb.length
                sb.append('\n')
                val sepEnd = sb.length
                if (gap > 0) {
                    sb.setSpan(
                        ParagraphGapLineHeightSpan(gap),
                        sepStart, sepEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        text = sb

        setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textPaint.textSize)
        typeface = style.textPaint.typeface
        setTextColor(style.textPaint.color)
        letterSpacing = style.textPaint.letterSpacing
        setLineSpacing(style.lineSpacingExtra, style.lineSpacingMultiplier.coerceAtLeast(0.8f))
        highlightColor = style.selectionColor
    }

    /**
     * Convert this block's local char offset to the source-absolute offset.
     * When [localOffset] falls inside a paragraph separator (between segments)
     * it snaps to the adjacent paragraph's edge.
     */
    fun localToAbsolute(localOffset: Int): Int {
        if (segments.isEmpty()) return 0
        for (seg in segments) {
            if (localOffset <= seg.localEnd) {
                val clamped = localOffset.coerceAtLeast(seg.localStart)
                return seg.absoluteStart + (clamped - seg.localStart)
            }
        }
        val last = segments.last()
        return last.absoluteStart + (last.localEnd - last.localStart)
    }

    private fun buildActionModeCallback(): ActionMode.Callback {
        return object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.clear()
                menuEntries.forEachIndexed { index, entry ->
                    menu.add(Menu.NONE, entry.id, index, entry.title)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                notifySelection(onSelectionStarted)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                // Called when handles move and the system refreshes — keep host's
                // `activeSelection` up-to-date so "复制" etc. always reflect the
                // latest handle positions.
                notifySelection(onSelectionChanged)
                return false
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val id = item.itemId
                if (id == ID_SELECT_ALL) {
                    performSelectAll()
                    // Keep the mode open — the refreshed selection will be
                    // reported via [onPrepareActionMode] + [notifySelection].
                    notifySelection(onSelectionChanged)
                    mode.invalidate()
                    return true
                }
                if (menuEntries.any { it.id == id }) {
                    notifySelection(onSelectionChanged)
                    onMenuAction?.invoke(id)
                    mode.finish()
                    return true
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                onSelectionEnded?.invoke(this@ReaderTextBlockView)
            }
        }
    }

    private fun notifySelection(cb: ((ReaderTextBlockView, Int, Int, CharSequence) -> Unit)?) {
        if (cb == null) return
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(start)
        val sliced = if (end > start && end <= text.length) text.subSequence(start, end) else ""
        cb(this, localToAbsolute(start), localToAbsolute(end), sliced)
    }

    /**
     * Transparent for clicks when nothing is selected — a bare tap should fall
     * through to the parent [NovelReaderView] so the tap-zones (flip / chrome
     * toggle) keep working on text areas. Long-press still goes to TextView via
     * super() so native selection starts; once a selection is active, TextView
     * consumes all motion (handles, drag).
     */
    private fun performSelectAll() {
        val t = text
        val spannable: Spannable = when (t) {
            is Spannable -> t
            else -> {
                val s = SpannableString(t)
                text = s
                s
            }
        }
        Selection.setSelection(spannable, 0, spannable.length)
    }

    /**
     * Adds [extraDescentPx] to the descent of the single line that contains
     * the spanned character(s). Applied to the `\n` between paragraphs so the
     * paragraph gap materialises *below* the last line of the prior
     * paragraph — no blank line, no extra row count, so the merged text keeps
     * the paginator's original line budget.
     */
    private class ParagraphGapLineHeightSpan(private val extraDescentPx: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: Paint.FontMetricsInt,
        ) {
            fm.descent += extraDescentPx
            fm.bottom += extraDescentPx
        }
    }

    companion object {
        /** Menu id reserved for the TextView's built-in "全选本页" action.
         *  Routed inside [ReaderTextBlockView] — host's [onMenuAction] is not
         *  called for this id. */
        const val ID_SELECT_ALL = -100
    }

    /**
     * TextView with `textIsSelectable = true` consumes every tap as a no-op
     * click, which kills the reader's flip-on-tap zones over text areas.
     * Android doesn't dispatch an intercepted UP to the parent's
     * `onTouchEvent` (only cancels the child), so the tap can't be rescued
     * upstream. Detect bare taps here and relay via [onBareTap].
     *
     * A bare tap means: DOWN and UP within a short time and small travel,
     * with no selection created or dismissed.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hadSelectionAtDown = selectionStart != selectionEnd
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            downTime = System.currentTimeMillis()
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val nowHasSelection = selectionStart != selectionEnd
            if (!hadSelectionAtDown && !nowHasSelection) {
                val elapsed = System.currentTimeMillis() - downTime
                val dx = event.x - downX
                val dy = event.y - downY
                val travel = kotlin.math.hypot(dx, dy)
                val slopPx = context.resources.displayMetrics.density * 8f
                if (elapsed < 260L && travel < slopPx) {
                    onBareTap?.invoke(event.x, event.y)
                }
            }
        }
        return handled
    }

    /**
     * Hard-disable internal vertical scrolling. A page is a fixed rect; any
     * scroll here means content drifted past the paginator's budget and the
     * right recovery is to clip, not to let the user pan the block inside
     * itself. `setTextIsSelectable(true)` installs ArrowKeyMovementMethod, and
     * Editor code paths (bringPointIntoView on cursor moves, long-press
     * selection auto-scroll, accessibility) can still call [scrollTo] /
     * [scrollBy] behind our back — neutralise those entry points.
     *
     * Horizontal scroll is left untouched: we never set
     * [setHorizontallyScrolling], so the TextView has no cause to scroll on X
     * anyway, and preserving `scrollTo(x, 0)` keeps bidi edge cases intact.
     */
    override fun canScrollVertically(direction: Int): Boolean = false

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, 0)
    }

    override fun scrollBy(x: Int, y: Int) {
        super.scrollBy(x, 0)
    }
}
