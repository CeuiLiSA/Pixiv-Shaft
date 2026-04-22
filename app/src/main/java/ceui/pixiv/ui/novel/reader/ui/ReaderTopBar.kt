package ceui.pixiv.ui.novel.reader.ui

import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.LayoutReaderTopBarBinding

class ReaderTopBar(private val binding: LayoutReaderTopBarBinding) {

    val view: View get() = binding.root

    var onBackClick: (() -> Unit)? = null
    var onAnnotationsClick: (() -> Unit)? = null
    var onBookmarkClick: (() -> Unit)? = null
    var onBookmarkLongClick: (() -> Unit)? = null
    var onMoreClick: (() -> Unit)? = null

    init {
        binding.btnBack.setOnClickListener { onBackClick?.invoke() }
        binding.btnAnnotations.setOnClickListener { onAnnotationsClick?.invoke() }
        binding.btnBookmark.setOnClickListener { onBookmarkClick?.invoke() }
        binding.btnBookmark.setOnLongClickListener {
            val cb = onBookmarkLongClick ?: return@setOnLongClickListener false
            cb.invoke()
            true
        }
        binding.btnMore.setOnClickListener { onMoreClick?.invoke() }
    }

    fun setTitle(title: String?) {
        binding.txtTitle.text = title.orEmpty()
    }

    fun setBookmarked(bookmarked: Boolean) {
        binding.btnBookmark.setImageResource(
            if (bookmarked) R.drawable.ic_reader_bookmark_filled else R.drawable.ic_reader_bookmark_border,
        )
    }
}
