package ceui.pixiv.plaza.ui

import androidx.core.os.bundleOf

class PlazaPostDetailFragment : PlazaTimelineFragment() {
    override val postId get() = arguments?.getLong(EXTRA_POST_ID) ?: 0L
    companion object {
        const val EXTRA_POST_ID = "plaza_post_id"
        fun newInstance(id: Long) = PlazaPostDetailFragment().apply { arguments = bundleOf(EXTRA_POST_ID to id) }
    }
}
