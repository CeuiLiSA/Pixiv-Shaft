package ceui.pixiv.ui.library

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentBookmarkLibraryBinding
import ceui.lisa.utils.Params
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.UserFeedFragment

/**
 * 关注库 —— 「我的关注」的本地镜像版，[BookmarkLibraryFragment] / [NovelBookmarkLibraryFragment]
 * 的第三个兄弟。
 *
 * pixiv 的关注列表同样只能从新到旧顺着翻，关注几千人之后想找「很早以前关注的那位」「好几年
 * 没更新的那些」只能一路滑。镜像到本地后可以倒序、按最近投稿排、按名字 / 账号 / 最近作品的
 * 标签搜 —— 引擎、续传、限速、全量重扫与收藏库是同一套（见 [ceui.pixiv.db.mirror.BookmarkShelf]）。
 *
 * 与两个兄弟一样，本类只负责「我是关注版」：继承用户卡列表基类（白拿关注切换、LIKED_USER
 * 跨列表同步、点进画师页），并把 [MirrorContentType.USER] 交给数据源和共用的 [BookmarkLibraryUi]。
 */
class FollowingLibraryFragment :
    UserFeedFragment(R.layout.fragment_bookmark_library),
    BookmarkFilterSheet.Host {

    private val initialShelf: BookmarkShelf by lazy(LazyThreadSafetyMode.NONE) {
        BookmarkLibraryUi.shelfFromArguments(requireArguments(), SessionManager.loggedInUid)
            .copy(contentType = MirrorContentType.USER)
    }

    private val libraryViewModel: BookmarkLibraryViewModel by viewModels()

    override val feedViewModel by feedViewModels {
        val vm = libraryViewModel.also { it.bind(initialShelf) }
        BookmarkLibraryFeedSource(vm, MirrorContentType.USER)
    }

    private var ui: BookmarkLibraryUi? = null

    override val emptyStateText: CharSequence
        get() = ui?.emptyStateText() ?: super.emptyStateText

    override fun onBookmarkFilterChanged() {
        ui?.applyFilterChange()
    }

    override fun onListCommitted(state: FeedUiState) {
        super.onListCommitted(state)
        ui?.onListCommitted(state)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        libraryViewModel.bind(initialShelf)
        ui = BookmarkLibraryUi(
            fragment = this,
            binding = FragmentBookmarkLibraryBinding.bind(view),
            listView = feedBinding.feedListView,
            viewModel = libraryViewModel,
            feedViewModel = feedViewModel,
            contentType = MirrorContentType.USER,
            itemCount = { feedViewModel.uiState.value.items.size },
        ).also { it.install() }
    }

    override fun onResume() {
        super.onResume()
        ui?.onResumed()
    }

    override fun onDestroyView() {
        ui?.destroy()
        ui = null
        super.onDestroyView()
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun newInstance(
            userId: Long = SessionManager.loggedInUid,
            starType: String = Params.TYPE_PUBLIC,
        ): FollowingLibraryFragment = FollowingLibraryFragment().apply {
            arguments = Bundle().apply {
                putLong(Params.USER_ID, userId)
                putString(Params.STAR_TYPE, starType)
                putInt(BookmarkLibraryUi.ARG_CONTENT_TYPE, MirrorContentType.USER.code)
            }
        }
    }
}
