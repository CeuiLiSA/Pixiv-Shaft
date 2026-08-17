package ceui.pixiv.ui.dynamic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.setUpToolbar

/**
 * 「关注画师的最新小说」列表（feeds 框架版，替代 legacy FragmentNewNovels + NewNovelRepo）。
 * 卡片 / 收藏同步 / 详情跳转 / 首屏骨架图全部复用基类 [NovelFeedFragment] 的主力小说卡（recy_novel）。
 *
 * 两种形态，对齐 legacy：
 * - **内嵌**（[ceui.lisa.fragments.FragmentRight] 的小说模式，showToolbar=false）：宿主自己有头，
 *   不能再带 toolbar（legacy 是 inflate 出来再把 R.id.toolbar 设 GONE，这里直接不 inflate）；
 * - **独立**（TemplateActivity「关注者的小说」，showToolbar=true）：自带返回箭头 + 标题。
 *
 * 筛选范围（全部 / 公开 / 私人）与插画列表同一套契约：值归 [RestrictViewModel]（零捕获 +
 * 跨视图重建存活，理由见该类），宿主通过 [setRestrict] 推进来，**变了才重拉**。
 * 这也修掉了 legacy 的一个隐患：legacy 把 restrict 记在 Fragment 字段 + arguments 里，
 * 旋转后 initBundle 会把它复位成「创建时」的那个值，与实际在显示的数据脱节。
 */
class FollowingNovelFeedFragment : NovelFeedFragment() {

    private val restrictViewModel: RestrictViewModel by viewModels()

    /** legacy 默认带 toolbar（TemplateActivity 走无参构造，hideToolbar=false），保持一致。 */
    private val showToolbar: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        arguments?.getBoolean(ARG_SHOW_TOOLBAR, true) ?: true
    }

    override val feedViewModel by feedViewModels {
        // 取成局部 val:捕获的是 VM 实例(与 FeedViewModel 同 store、同寿命),不是 Fragment
        val holder = restrictViewModel
        pixivFeedSource({ Client.appApi.getFollowingNovels(holder.restrict) }) { resp, _ ->
            mapFollowingNovelPage(resp.displayList)
        }
    }

    /**
     * 内嵌动态页那张圆角 sheet 时，底色要跟 sheet 同源，否则会裂出一道撞色横缝
     * （取值理由见 [FollowingIllustFeedFragment.feedRootBackgroundColor]，两条列表必须一致）。
     * 独立页走 fragment_toolbar_feed，根布局不是 feed_root，基类不会用到本属性。
     */
    override val feedRootBackgroundColor: Int
        get() = V3Palette.from(requireContext()).cardFill

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只在首次创建时用宿主给的初始 restrict 播种：必须赶在 feedViewModel（onViewCreated 里
        // 才建）之前写好，否则 autoLoad 的首屏会先按默认「全部」拉一次、再被 setRestrict 重拉一次。
        // 重建（旋转/深色）时 VM 里已经是最新值，不能被 arguments 里那份「创建时」的旧值盖回去
        // ——legacy 就是每次 initBundle 都从 arguments 复位，才有了上面类文档说的脱节。
        if (savedInstanceState == null) {
            arguments?.getString(ARG_RESTRICT)?.takeIf { it.isNotEmpty() }?.let {
                restrictViewModel.restrict = it
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // showToolbar 是运行时参数，系统重建只走无参构造，不能靠构造器传 contentLayoutId，
        // 改在这里按参数选骨架（两张布局都带同结构的 feed_root）。对齐 UserNovelFeedFragment。
        val layoutId = if (showToolbar) R.layout.fragment_toolbar_feed else ceui.pixiv.feeds.R.layout.fragment_feed
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!showToolbar) return
        val binding = FragmentToolbarFeedBinding.bind(view)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.setText(R.string.string_197) // 关注者的最新小说
    }

    /**
     * 切筛选范围（宿主筛选条选中另一项时调）。对齐 legacy FragmentNewNovels.setRestrict：
     * **变了才重拉**，没变是 no-op。
     */
    fun setRestrict(restrict: String) {
        if (restrictViewModel.restrict == restrict) return
        restrictViewModel.restrict = restrict
        // forceRefresh 在 view 未创建时安全 no-op：此时首屏也还没拉过，
        // 会自然用上面刚写进去的新 restrict
        forceRefresh()
    }

    companion object {
        const val ARG_SHOW_TOOLBAR = "show_toolbar"
        const val ARG_RESTRICT = "restrict"

        @JvmStatic
        @JvmOverloads
        fun newInstance(
            showToolbar: Boolean = true,
            restrict: String = Params.TYPE_ALL,
        ): FollowingNovelFeedFragment {
            return FollowingNovelFeedFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_SHOW_TOOLBAR, showToolbar)
                    putString(ARG_RESTRICT, restrict)
                }
            }
        }

        /** 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。 */
        private fun mapFollowingNovelPage(novels: List<Novel>): List<FeedItem> {
            return novels.mapNotNull { NovelFeedItem.of(it) }
        }
    }
}
