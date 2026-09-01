package ceui.pixiv.ui.library

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentBookmarkLibraryBinding
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.IllustFeedFragment

/**
 * 收藏库（插画/漫画）—— 直接看本地镜像表（[ceui.pixiv.db.mirror.BookmarkMirrorEntity]）的收藏列表页。
 *
 * ## 它和「我的插画收藏」是什么关系
 *
 * 「我的插画收藏」是**服务端顺序**的原样列表：只能从新到旧，翻到哪算哪。本页是同一批
 * 收藏的**本地副本**，所以能做服务端做不到的一切：倒序（友商 pixez #1323 的诉求）、
 * 按标签/作者/年份/画幅/人气筛、全文搜、随机漫游 —— 而且全在 SQLite 里，一次网络请求都不发。
 *
 * 两者不是替代关系：镜像还没补齐的时候，原列表永远是最新最全的那份。所以本页在补齐之前
 * 会挂一条进度条老实说「还在补」，补齐之后那条就永远消失。
 *
 * ## 本类只剩下「我是插画版」这一件事
 *
 * 页面的全部接线（toolbar、公开/悄悄切换、搜索、chip 行、筛选面板、进度条、自动重查）
 * 在 [BookmarkLibraryUi] 里，与小说版 [NovelBookmarkLibraryFragment] 共用同一份。
 * 本类只负责：继承插画列表基类拿到瀑布流卡，以及给数据源指定 [MirrorContentType.ILLUST]。
 *
 * ## 数据取舍
 *
 * 卡片 bean 来自镜像行里冻结的 JSON，所以：
 * - 不喂 ObjectPool（[poolableBeansOf] 返回空）—— 旧快照会盖掉用户这次会话里更新的收藏/关注态；
 * - 不给详情页续拉游标（[detailContinuationCursor] 恒 null）—— 本地 offset 流到那条路上会被当 URL 请求。
 * 两条的完整论证同「稍后再看」页（`WatchLaterFeedFragment`）。
 */
class BookmarkLibraryFragment :
    IllustFeedFragment(R.layout.fragment_bookmark_library),
    BookmarkFilterSheet.Host {

    private val initialShelf: BookmarkShelf by lazy(LazyThreadSafetyMode.NONE) {
        BookmarkLibraryUi.shelfFromArguments(requireArguments(), SessionManager.loggedInUid)
            .copy(contentType = MirrorContentType.ILLUST)
    }

    private val libraryViewModel: BookmarkLibraryViewModel by viewModels()

    override val feedViewModel by feedViewModels {
        // 零捕获：只捕获兄弟 VM（同一 ViewModelStore、同生命周期），不碰 Fragment。
        // bind 是幂等的，谁先初始化都行。
        val vm = libraryViewModel.also { it.bind(initialShelf) }
        BookmarkLibraryFeedSource(vm, MirrorContentType.ILLUST)
    }

    private var ui: BookmarkLibraryUi? = null

    override val detailContinuationCursor: String? get() = null

    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    /**
     * 本页看的就是「我的收藏」，所以和 [ceui.pixiv.ui.collection.LikeIllustFeedFragment] 一样
     * 尊重「收藏页隐藏收藏按钮」设置——同一批内容换个入口就多出一排爱心，是前后不一致。
     */
    override val hideLikeButton: Boolean
        get() = SessionManager.loggedInUid == libraryViewModel.shelf.ownerUid &&
                Shaft.sSettings.isHideStarButtonAtMyCollection()

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
            contentType = MirrorContentType.ILLUST,
            itemCount = { feedViewModel.uiState.value.items.size },
        ).also { it.install() }
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
        ): BookmarkLibraryFragment = BookmarkLibraryFragment().apply {
            arguments = Bundle().apply {
                putLong(Params.USER_ID, userId)
                putString(Params.STAR_TYPE, starType)
                putInt(BookmarkLibraryUi.ARG_CONTENT_TYPE, MirrorContentType.ILLUST.code)
            }
        }
    }
}
