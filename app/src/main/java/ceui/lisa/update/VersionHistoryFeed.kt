package ceui.lisa.update

import android.content.Context
import android.util.AttributeSet
import ceui.lisa.BuildConfig
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.SkeletonBlock
import java.util.Locale

/** `v4.9.0` / `V4.9.0` → `4.9.0`，全包只在这里剥一次前缀。 */
internal val GitHubRelease.versionName: String
    get() = tagName.removePrefix("v").removePrefix("V")

/** 45.6 MB。安装包大小只有一位小数有意义，且要跟着系统语言走小数点。 */
internal fun formatApkSize(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576f)

/**
 * 顶部汇总：当前版本 + 这一页唯一的主操作。
 *
 * [update] 为 null 表示「没有可更新的版本」——已是最新，或者本渠道（Google Play）根本
 * 不从 GitHub 更新，两种情况的文案由视图按 [BuildConfig.IS_LITE] 分流。
 */
internal data class VersionSummaryItem(
    val currentVersion: String,
    val update: GitHubRelease?,
) : FeedItem {
    override val feedKey: Any get() = "summary"
}

/** 一条发布记录。[expanded] 是列表持有的展开态，翻页/回收都不会丢。 */
internal data class ReleaseItem(
    val release: GitHubRelease,
    val isCurrent: Boolean,
    val isLatest: Boolean,
    val expanded: Boolean = false,
) : FeedItem {
    override val feedKey: Any get() = release.tagName
}

/**
 * 版本历史的数据源：GitHub 的 releases 一次返回完整一页，所以 `nextCursor` 恒为 null，
 * feeds 不会再往后翻。空列表原样返回，空态交给框架。
 *
 * 刻意不调 [AppUpdateChecker.markChecked]：那个时间戳管的是关于页的每日自动检查，
 * 用户来翻历史不代表他已经看过更新提示。
 *
 * 三个参数都只为单测留口子（版本号、渠道、取数），线上一律走默认值；数据源归 VM 长期持有，
 * 默认值里也不能捕获 Fragment。
 */
internal class VersionHistorySource(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val fromStore: Boolean = BuildConfig.IS_LITE,
    private val fetch: suspend () -> List<GitHubRelease> = { AppUpdateChecker.fetchAllReleases() },
) : FeedSource<Unit> {

    override suspend fun load(cursor: Unit?): FeedPage<Unit> {
        val releases = fetch()
        if (releases.isEmpty()) return FeedPage(emptyList(), null)
        val current = currentVersion
        val update = releases.first()
            .takeIf { !fromStore && AppUpdateChecker.isNewerVersion(it.versionName, current) }
        val items = ArrayList<FeedItem>(releases.size + 1)
        items.add(VersionSummaryItem(current, update))
        releases.forEachIndexed { index, release ->
            items.add(ReleaseItem(release, release.versionName == current, index == 0))
        }
        return FeedPage(items, null)
    }
}

/**
 * 首屏骨架：画的是汇总卡和发布卡的**内容**（图标块、版本号、日期、三行说明），
 * 不是两块实心板 —— 真卡片换上来时几乎原地替换，不会整屏跳一下。
 *
 * 左右都要同时算上列表 padding(20dp) 和卡片 padding(18dp)。
 */
internal class VersionHistorySkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val d = density
        val left = 38 * d
        val right = w - 38 * d
        val width = (right - left).coerceAtLeast(0f)
        if (width <= 0f) return
        val corner = 4 * d

        // 汇总卡：图标容器 + 两行版本号 + 一条主操作胶囊
        var y = 16 * d
        out.add(block(left, y + 20 * d, 48 * d, 48 * d, 16 * d))
        out.add(block(left + 62 * d, y + 24 * d, 96 * d, 12 * d, corner))
        out.add(block(left + 62 * d, y + 46 * d, 130 * d, 22 * d, corner))
        out.add(block(left, y + 96 * d, width, 14 * d, corner))
        out.add(block(left, y + 128 * d, minOf(200 * d, width), 44 * d, 22 * d))
        y += 200 * d

        // 发布卡：版本号 + 日期 + 标题 + 三行说明
        while (y < h) {
            out.add(block(left, y + 18 * d, 88 * d, 20 * d, corner))
            out.add(block(right - 76 * d, y + 21 * d, 76 * d, 14 * d, corner))
            out.add(block(left, y + 52 * d, width * .55f, 16 * d, corner))
            out.add(block(left, y + 82 * d, width, 12 * d, corner))
            out.add(block(left, y + 102 * d, width * .82f, 12 * d, corner))
            out.add(block(left, y + 122 * d, width * .46f, 12 * d, corner))
            y += 164 * d
        }
    }
}
