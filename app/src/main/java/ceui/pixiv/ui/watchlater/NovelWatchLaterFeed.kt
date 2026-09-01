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
import ceui.loxia.Novel
import ceui.pixiv.db.EntityType
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.db.RecordType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「稍后再看」小说 tab。宿主 [WatchLaterTabsFragment]（对齐浏览记录页的 tab 结构）。
 *
 * 卡片 / 收藏同步 / 长按菜单全部复用 [NovelFeedFragment] 的主力小说卡（recy_novel）。
 * 本页是纯列表（fragment_feed，无自带 toolbar），工具栏由宿主出；「清空」也只清小说
 *（clearNovelWatchLater 只删 WATCH_LATER_NOVEL，插画 tab 不受影响）。
 */
class NovelWatchLaterFeedFragment : NovelFeedFragment() {

    override val feedViewModel by feedViewModels {
        NovelWatchLaterFeedSource()
    }

    // 裸 fragment_feed + 宿主底部没有 BottomNavigation：不补 systemBars inset 的话，
    // 最后一张卡会压在手势条/导航栏底下（原插画页那份 padding 来自 setUpToolbar，
    // 改成 tab 结构后那条路没了）。
    override val applyBottomSafeInset: Boolean = true

    override val emptyStateText: CharSequence
        get() = getString(R.string.watch_later_empty)

    private val changeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 插画那半边的增删跟本页无关，重拉一次是整表 Gson 反序列化，白花。
            // 读不到类型（老广播）就照常刷，不赌。
            if (intent?.getIntExtra(EntityWrapper.EXTRA_ENTITY_TYPE, -1) == EntityType.ILLUST) return
            feedViewModel.refresh()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(changeReceiver, IntentFilter(EntityWrapper.ACTION_WATCH_LATER_CHANGED))
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(changeReceiver)
        super.onDestroyView()
    }
}

/**
 * 小说稍后再看的数据源：general_table(WATCH_LATER_NOVEL) 全量单页，没有翻页
 *（nextCursor 恒为 null）。存的是 Novel JSON，还原时不过全局过滤
 *（[NovelFeedItem.rawFromNovel]）。
 */
class NovelWatchLaterFeedSource : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val items: List<FeedItem> = withContext(Dispatchers.IO) {
            AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()
                .getByRecordType(RecordType.WATCH_LATER_NOVEL, 0, Int.MAX_VALUE)
                .mapNotNull { entity ->
                    val novel = runCatching {
                        Shaft.sGson.fromJson(entity.json, Novel::class.java)
                    }.getOrNull()
                    NovelFeedItem.rawFromNovel(novel)
                }
        }
        return FeedPage(items, null)
    }
}
