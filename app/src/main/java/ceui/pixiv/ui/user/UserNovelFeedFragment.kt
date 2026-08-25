package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.activities.UserNovelFirstPageListener
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import kotlinx.coroutines.launch

/**
 * 「某人创作的小说」列表页（feeds 框架版，替代 legacy FragmentUserNovel + UserNovelRepo）。
 * 卡片 / 收藏同步 / 详情跳转全部复用基类 [NovelFeedFragment] 的主力小说卡（recy_novel），
 * 首屏骨架图也由基类给成小说卡的形状。
 *
 * 两种形态，对齐 legacy：
 * - 内嵌（UserActivityV3 小说 Tab，showToolbar=false）：不能带 toolbar，否则 Tab 里会多一条头；
 * - 独立（TemplateActivity「小说作品」，showToolbar=true）：自带返回箭头 + 标题。
 *
 * legacy 没有插画/漫画页那套 toolbar 菜单（收藏到精华 / 跳转），这里同样不加。
 */
class UserNovelFeedFragment : NovelFeedFragment() {

    private val userId: Int by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(Params.USER_ID)
    }

    /** legacy 默认 true（newInstance(userID) 单参版），保持一致。 */
    private val showToolbar: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getBoolean(Params.FLAG, true)
    }

    /** 作者页状态小 VM：持有「整页被过滤滤空」标记（空态文案用）；小说页不消费 total。 */
    private val userWorksStateViewModel: UserWorksStateViewModel by viewModels()

    // 内嵌 UserActivityV3 tab(无底栏)时,列表底部补手势条 inset;带 toolbar 独立页由 setUpToolbar 自理
    override val applyBottomSafeInset: Boolean = true

    /** 作者小说被屏蔽设置（AI / R18 / 标签…）整页滤空时，在通用空态下方换行追加说明。 */
    override val emptyStateText: CharSequence
        get() = filteredEmptyStateText(
            super.emptyStateText,
            userWorksStateViewModel.allItemsFiltered.value,
            requireContext(),
        )

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定:userId 先取成局部值,不把 Fragment 钉进长命 VM
        val uid = userId.toLong()
        // userWorksStateViewModel 是 ViewModel 实例（非 Fragment），mapper 里借它记录「整页被过滤滤空」。
        val stateVm = userWorksStateViewModel
        pixivFeedSource({ Client.appApi.getUserCreatedNovels(uid) }) { resp, _ ->
            mapUserNovelPage(resp.displayList, stateVm)
        }
    }

    // showToolbar 是运行时参数,系统重建只走无参构造,不能靠构造器传 contentLayoutId,
    // 改在这里按参数选骨架(两张布局都带同结构的 feed_root)。对齐 UserIllustFeedFragment。
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val layoutId = if (showToolbar) R.layout.fragment_toolbar_feed else ceui.pixiv.feeds.R.layout.fragment_feed
        return inflater.inflate(layoutId, container, false)
    }

    /** 首屏只交付一次(标签筛选条聚合);旋转重建后 VM 已有数据会再交付一次(宿主自去重)。 */
    private var firstPageDelivered = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 首屏内容到手 → 交付宿主(小说 Tab 的标签筛选条聚合,issue #996)。
        // 对齐插画侧 UserIllustFeedFragment:观察 uiState,取第一次非空 items,不侵入渲染链路。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { state ->
                    if (!firstPageDelivered && state.items.isNotEmpty()) {
                        firstPageDelivered = true
                        val novels = state.items.filterIsInstance<NovelFeedItem>().map { it.novel }
                        val listener = parentFragment as? UserNovelFirstPageListener
                            ?: activity as? UserNovelFirstPageListener
                        listener?.onUserNovelFirstPage(novels)
                    }
                }
            }
        }

        // allItemsFiltered 由 mapper 在后台设置，render 可能先于它跑；停在空态时补一次文案。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                userWorksStateViewModel.allItemsFiltered.collect {
                    if (feedViewModel.uiState.value.showEmptyState) {
                        feedBinding.feedStateText.text = emptyStateText
                    }
                }
            }
        }

        if (!showToolbar) return
        val binding = FragmentToolbarFeedBinding.bind(view)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.setText(R.string.string_237) // 小说作品
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun newInstance(userID: Int, showToolbar: Boolean = true): UserNovelFeedFragment {
            return UserNovelFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.USER_ID, userID)
                    putBoolean(Params.FLAG, showToolbar)
                }
            }
        }

        /** 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。 */
        private fun mapUserNovelPage(
            novels: List<Novel>,
            stateVm: UserWorksStateViewModel,
        ): List<FeedItem> {
            val items = novels.mapNotNull { NovelFeedItem.of(it, skipMuteUserFilter = true) }
            // 空态要能说清「作者有作品，只是被你的屏蔽设置全滤掉了」，和「作者确实没作品」区分开。
            stateVm.reportPageFiltered(rawCount = novels.size, shownCount = items.size)
            return items
        }
    }
}
