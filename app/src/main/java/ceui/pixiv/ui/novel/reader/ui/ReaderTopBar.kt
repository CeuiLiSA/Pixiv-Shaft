package ceui.pixiv.ui.novel.reader.ui

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import ceui.lisa.R

/**
 * Thin wrapper around the top-bar include. Owns the button click callbacks and
 * exposes helpers to update the title + bookmark state without the host
 * Fragment having to know about the underlying view hierarchy.
 */
class ReaderTopBar(private val rootView: View) {

    private val btnBack = rootView.findViewById<ImageButton>(R.id.btn_back)
    private val btnBookmark = rootView.findViewById<ImageButton>(R.id.btn_bookmark)
    private val btnMore = rootView.findViewById<ImageButton>(R.id.btn_more)
    private val txtTitle = rootView.findViewById<TextView>(R.id.txt_title)

    val view: View get() = rootView

    var onBackClick: (() -> Unit)? = null
    var onBookmarkClick: (() -> Unit)? = null
    var onMoreClick: (() -> Unit)? = null

    init {
        btnBack.setOnClickListener { onBackClick?.invoke() }
        btnBookmark.setOnClickListener { onBookmarkClick?.invoke() }
        btnMore.setOnClickListener { onMoreClick?.invoke() }
    }

    fun setTitle(title: String?) {
        txtTitle.text = title.orEmpty()
    }

    fun setBookmarked(bookmarked: Boolean) {
        btnBookmark.setImageResource(
            if (bookmarked) R.drawable.ic_reader_bookmark_filled else R.drawable.ic_reader_bookmark_border
        )
    }
}
