package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.IllustResponse
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.PixivFeedSource
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ceui.pixiv.services.appServices

/**
 * 「相关作品」页（feeds 框架版，替代 legacy FragmentRelatedIllust + RelatedIllustRepo + IAdapter）。
 * TemplateActivity 宿主、自带 toolbar（fragment_toolbar_feed），卡片复用 [IllustFeedFragment] 的
 * 标准瀑布流插画卡。
 *
 * 与 legacy 对齐：
 * - toolbar 标题 = 作品标题 + 「的相关作品」；toolbar 菜单保留「收藏到精华」(local_save 去掉
 *   action_jump/action_add)，把当前列表存进 FeatureEntity 精华库；
 * - 每页过滤前整页喂 DiscoveryPool（对齐 RelatedIllustRepo.doOnNext）；
 * - 翻页门控：`isRelatedIllustNoLimit`（「相关作品无限下滑」）关时只出首页。legacy 的 hasNext
 *   只挡住下拉页脚、挡不住滚动预载（scroll-preload 绕过 hasNext 照常翻页），等于设置形同虚设；
 *   这里让设置在唯一的翻页路径上真正生效（对齐 [[feedback_settings_apply_everywhere]] 的一致性要求）。
 */
class RelatedIllustFeedFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // legacy 用 int ILLUST_ID（TemplateActivity 路由 getIntExtra、ArtworkDetailItem.RelatedHeader.illustId:Int）。
    private val illustId: Long by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(Params.ILLUST_ID).toLong()
    }
    private val title: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.ILLUST_TITLE).orEmpty()
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：先把 Fragment 属性取成局部 val 再进 source 的 lambda
        val illustId = illustId
        // 应用级对象，捕获它不会把 Fragment 钉进 VM
        val pool = requireContext().appServices().discoveryPool
        pixivFeedSource(
            initialFetch = { Client.appApi.getRelatedIllusts(illustId) },
            // 翻页门控：「相关作品无限下滑」关时只出首页（nextCursor=null 让 loadMore 直接 return，
            // 对齐 legacy RelatedIllustRepo.hasNext）。每次翻页现读设置，与旧实现同语义。
            nextCursorOf = { resp ->
                if (Shaft.sSettings.isRelatedIllustNoLimit) {
                    resp.next_url?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            },
        ) { resp, phase -> mapRelatedPage(pool, resp.illusts, phase, illustId) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = title + getString(R.string.string_231)
        binding.toolbar.inflateMenu(R.menu.local_save)
        // 对齐 legacy：只留「收藏到精华」，去掉跳转/添加。
        binding.toolbar.menu.removeItem(R.id.action_jump)
        binding.toolbar.menu.removeItem(R.id.action_add)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_bookmark) {
                saveToFeatures()
                true
            } else {
                false
            }
        }
    }

    /** 把当前已加载的相关作品存进精华库（对齐 legacy action_bookmark）。序列化 + Room 写挪 IO 避免卡主线程。 */
    private fun saveToFeatures() {
        val beans = ArrayList(currentIllustItems().map { it.illust })
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val entity = FeatureEntity().apply {
                    uuid = "${illustId}相关作品"
                    dataType = "相关作品"
                    illustID = illustId.toInt()
                    illustTitle = title
                    illustJson = Common.cutToJson(beans)
                    dateTime = System.currentTimeMillis()
                }
                AppDatabase.getAppDatabase(appCtx).downloadDao().insertFeature(entity)
            }
            Common.showToast("已收藏到精华")
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(id: Int, title: String?): RelatedIllustFeedFragment {
            return RelatedIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.ILLUST_ID, id)
                    putString(Params.ILLUST_TITLE, title)
                }
            }
        }
    }
}

/** 跑在 Default 线程（[PixivFeedSource] 派发）、被 VM 长期持有，放顶层保证零捕获。 */
private fun mapRelatedPage(
    pool: DiscoveryPool,
    illusts: List<Illust>,
    phase: FeedLoadPhase,
    illustId: Long,
): List<FeedItem> {
    // 对齐 RelatedIllustRepo.doOnNext：过滤前整页喂 DiscoveryPool。喂画像池是「拉取成功」型
    // 副作用，按 phase 门控——本源目前没配缓存（CacheRestore 走不到），但门控写在这里，
    // 将来给它开本地优先时不会拿磁盘上的旧数据重放去污染画像池。
    if (phase.isFreshFetch) {
        pool.collect(
            illusts,
            if (phase.isFirstPage) "related:$illustId" else "related_next:$illustId",
        )
    }
    return illusts.mapNotNull { IllustFeedItem.of(it) }
}
