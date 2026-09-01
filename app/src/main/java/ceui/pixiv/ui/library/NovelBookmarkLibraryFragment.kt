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
import ceui.pixiv.ui.common.NovelFeedFragment

/**
 * 收藏库（小说）—— [BookmarkLibraryFragment] 的小说版。
 *
 * 两者的差别**只有两处**：继承的列表基类（小说卡 [NovelFeedFragment] vs 瀑布流插画卡），
 * 以及交给数据源的 [MirrorContentType]。页面的全部接线在共用的 [BookmarkLibraryUi] 里，
 * 连筛选面板都是同一个（它按书架的内容类型自己换掉「作品类型 / 画幅 / 页数」那几节，
 * 补上「字数」排序）。
 *
 * 小说侧没有插画侧那两条覆写：
 * - 没有 `poolableBeansOf` —— [NovelFeedFragment] 本来就不往 ObjectPool 灌列表 bean；
 * - 没有 `hideLikeButton` —— 「收藏页隐藏收藏按钮」那个设置只作用于插画卡
 *   （对齐 legacy：小说收藏页用的是裸 NAdapter，从来没有那套爱心门控）。
 */
class NovelBookmarkLibraryFragment :
    NovelFeedFragment(R.layout.fragment_bookmark_library),
    BookmarkFilterSheet.Host {

    private val initialShelf: BookmarkShelf by lazy(LazyThreadSafetyMode.NONE) {
        BookmarkLibraryUi.shelfFromArguments(requireArguments(), SessionManager.loggedInUid)
            .copy(contentType = MirrorContentType.NOVEL)
    }

    private val libraryViewModel: BookmarkLibraryViewModel by viewModels()

    override val feedViewModel by feedViewModels {
        val vm = libraryViewModel.also { it.bind(initialShelf) }
        BookmarkLibraryFeedSource(vm, MirrorContentType.NOVEL)
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
            contentType = MirrorContentType.NOVEL,
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
        ): NovelBookmarkLibraryFragment = NovelBookmarkLibraryFragment().apply {
            arguments = Bundle().apply {
                putLong(Params.USER_ID, userId)
                putString(Params.STAR_TYPE, starType)
                putInt(BookmarkLibraryUi.ARG_CONTENT_TYPE, MirrorContentType.NOVEL.code)
            }
        }
    }
}
