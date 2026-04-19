package ceui.pixiv.ui.novel.reader.ui

import android.view.View
import android.widget.TextView
import ceui.lisa.R
import ceui.pixiv.ui.novel.reader.model.HighlightColor

/**
 * Floating menu that appears above an active text selection. Hosts all word-
 * level actions. Click callbacks are routed to the host Fragment so system
 * services (clipboard, intents, DB) stay out of this view class.
 */
class ReaderSelectionToolbar(private val rootView: View) {

    val view: View get() = rootView

    var onCopy: (() -> Unit)? = null
    var onShare: (() -> Unit)? = null
    var onQuoteCard: (() -> Unit)? = null
    var onSearchPixiv: (() -> Unit)? = null
    var onSearchWeb: (() -> Unit)? = null
    var onTranslate: (() -> Unit)? = null
    var onNote: (() -> Unit)? = null
    var onHighlight: ((HighlightColor) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    init {
        rootView.visibility = View.GONE

        rootView.findViewById<TextView>(R.id.sel_copy).setOnClickListener { onCopy?.invoke() }
        rootView.findViewById<TextView>(R.id.sel_share).setOnClickListener { onShare?.invoke() }
        rootView.findViewById<TextView>(R.id.sel_quote_card).setOnClickListener { onQuoteCard?.invoke() }
        rootView.findViewById<TextView>(R.id.sel_search_pixiv).setOnClickListener { onSearchPixiv?.invoke() }
        rootView.findViewById<TextView>(R.id.sel_search_web).setOnClickListener { onSearchWeb?.invoke() }
        rootView.findViewById<TextView>(R.id.sel_translate).setOnClickListener { onTranslate?.invoke() }
        rootView.findViewById<TextView>(R.id.sel_note).setOnClickListener { onNote?.invoke() }
        rootView.findViewById<TextView>(R.id.sel_dismiss).setOnClickListener { onDismiss?.invoke() }

        rootView.findViewById<TextView>(R.id.sel_highlight_yellow).setOnClickListener { onHighlight?.invoke(HighlightColor.Yellow) }
        rootView.findViewById<TextView>(R.id.sel_highlight_green).setOnClickListener { onHighlight?.invoke(HighlightColor.Green) }
        rootView.findViewById<TextView>(R.id.sel_highlight_pink).setOnClickListener { onHighlight?.invoke(HighlightColor.Pink) }
        rootView.findViewById<TextView>(R.id.sel_highlight_blue).setOnClickListener { onHighlight?.invoke(HighlightColor.Blue) }
    }

    /**
     * Position the toolbar above the selection's top. The bar spans full
     * parent width (HorizontalScrollView handles overflow internally), so we
     * only need to set Y. xCenter is kept in the signature for API stability.
     */
    fun showAt(xCenter: Float, yTop: Float, parentWidth: Int, parentHeight: Int) {
        rootView.visibility = View.VISIBLE
        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(parentHeight, View.MeasureSpec.AT_MOST),
        )
        val h = rootView.measuredHeight
        val targetY = (yTop - h - 12f).coerceAtLeast(0f)
        rootView.x = 0f
        rootView.y = targetY
    }

    fun hide() {
        rootView.visibility = View.GONE
    }
}
