package ceui.pixiv.ui.watchlater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.IllustsBean
import ceui.pixiv.db.EntityType
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.db.RecordType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「稍后再看」列表页（feeds 框架版，替代 legacy WatchLaterFragment + WatchLaterViewModel + IAdapter）。
 *
 * 迁 feeds 顺带解掉了本页原来那条硬约束：以前必须手写 legacy IAdapter，因为 V3 的
 * IllustCardHolder 点击走 findNavController，而宿主 TemplateActivity 不是 NavHost，点进详情必崩。
 * [IllustFeedFragment] 的卡片点击本来就走 PageData + Container + VActivity（见其类注释），
 * 不碰 NavController，所以这里能直接用标准瀑布流卡，还白拿长按菜单 / 收藏红心广播同步。
 *
 * toolbar 用 feeds 独立页统一的 fragment_toolbar_feed（webview 5 件套），不再是本页原来那套
 * layout_toolbar；原 navi_more 的入口换成 toolbar 菜单里的「更多」，点开仍是同一个 V3 菜单 sheet。
 *
 * 现状：入口改为 tab 结构（[WatchLaterTabsFragment]，对齐浏览记录页）后，本页不再自带 toolbar，
 * 返回 / 标题 / 「更多」菜单（播放全部、清空）统一由宿主出，本页只负责列表本体。
 */
class WatchLaterFeedFragment : IllustFeedFragment() {

    override val feedViewModel by feedViewModels {
        // 零捕获：source 不吃任何参数，DB 走 application context。
        WatchLaterFeedSource()
    }

    // 本页原来是 fragment_toolbar_feed，底部 systemBars inset 由 setUpToolbar 顺手吃掉；
    // 改成裸 fragment_feed 交给 tab 宿主后那条路没了，得自己补，否则末条卡压在手势条底下。
    override val applyBottomSafeInset: Boolean = true

    /**
     * 本地数据源不给详情页 pager 续读游标（基类 KDoc：本地源必须覆写成 null）。
     * 本页的 source 每次都返回 nextCursor = null，基类默认值算出来其实也是 null；
     * 这里显式写死是防呆——万一哪天真给本页加了翻页，游标是 offset 之类的东西，
     * 漏掉这条就会被详情页当 @Url 直接请求。
     */
    override val detailContinuationCursor: String? get() = null

    /**
     * **不把本页的 bean 合进 ObjectPool / 全局关注态。**
     *
     * 基类默认会把列表 bean 喂给 [ceui.pixiv.ui.common.IllustFeedPoolSync]，因为别的 feed 页
     * 拿的都是刚下行的新鲜数据。本页不是：general_table 存的是**加入稍后再看那一刻**冻结的 JSON，
     * 之后再没更新过。喂进去会拿旧值盖掉新值——ObjectPool.mergeKeepingExisting 只把
     * null/空串/空数组当「空」，`is_bookmarked=false`、`total_bookmarks=100` 是正经 JSON 原始值，
     * 照盖不误；AppLevelViewModelHelper.fill 那条更狠，旧的 is_followed=false 会把用户这次会话里
     * 刚点的「已关注」打回「未关注」（AppLevelViewModel 只在传入 FOLLOWED 时才早退）。
     *
     * 关掉不影响从本页点进详情：VActivity 只在池里 miss 时才用 PageData 的 bean 填池
     *（见 VActivity `if (exist == null)`），不会顶掉更新的那份。legacy IAdapter 路径同样从不写池。
     */
    override fun poolableBeansOf(item: FeedItem): List<IllustsBean> = emptyList()

    /** 空态维持本页专属文案，不退化成通用的「居然啥也没有」。 */
    override val emptyStateText: CharSequence
        get() = getString(R.string.watch_later_empty)

    private val changeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 小说那半边的增删跟本页无关，重拉一次是整表 Gson 反序列化，白花。
            // 读不到类型（老广播）就照常刷，不赌。
            if (intent?.getIntExtra(EntityWrapper.EXTRA_ENTITY_TYPE, -1) == EntityType.NOVEL) return
            feedViewModel.refresh()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(changeReceiver, IntentFilter(EntityWrapper.ACTION_WATCH_LATER_CHANGED))
    }

    // 不在 onResume 里 refresh：增删清空经 EntityWrapper 发 WATCH_LATER_CHANGED 广播，
    // changeReceiver 从 onViewCreated 到 onDestroyView 全程注册（在后台也收得到），已覆盖所有
    // 列表变更。收藏红心不走这条路（DB 存的是加入时的旧 JSON，重拉也 stale），硬 refresh 只会
    // 全表重解析做无用功，还会把列表内乐观点心回弹成旧值。

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(changeReceiver)
        super.onDestroyView()
    }

    /** 列表当前快照：供「播放全部」「清空前判空」取用（原 WatchLaterViewModel.current）。 */
    internal fun currentBeans(): List<IllustsBean> = currentIllustItems().map { it.bean }
}

/**
 * 「稍后再看」数据源：general_table(WATCH_LATER) 全量单页，没有翻页（nextCursor 恒为 null，
 * 对齐 legacy 的 setEnableLoadMore(false)）。存的是 IllustsBean JSON（字段名与 loxia Illust
 * 完全一致）。
 *
 * 用 [IllustFeedItem.rawFromBean] 而不是 fromBean：**这里的条目是用户手动存进来的，不该再被
 * 全局内容过滤（R18 / 屏蔽标签 / 屏蔽画师 / 屏蔽 AI）二次筛掉**——存的时候能存，回来就得看得见，
 * 否则改一下设置列表就凭空少几张，还找不回来。legacy IAdapter 路径同样不过滤，此处即对齐。
 *
 * 零 Fragment 捕获：无参构造，DB 走 [Shaft.getContext] 的 application context。
 *
 * 小说的稍后再看是独立的 RecordType.WATCH_LATER_NOVEL（隔壁 NovelWatchLaterFeedSource），
 * 本表只有插画，小说 JSON 绝不会被解析成一张坏插画卡。
 */
class WatchLaterFeedSource : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val items: List<FeedItem> = withContext(Dispatchers.IO) {
            AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()
                .getByRecordType(RecordType.WATCH_LATER, 0, Int.MAX_VALUE)
                .mapNotNull { entity ->
                    val bean = runCatching {
                        Shaft.sGson.fromJson(entity.json, IllustsBean::class.java)
                    }.getOrNull()
                    IllustFeedItem.rawFromBean(bean)
                }
        }
        return FeedPage(items, null)
    }
}
