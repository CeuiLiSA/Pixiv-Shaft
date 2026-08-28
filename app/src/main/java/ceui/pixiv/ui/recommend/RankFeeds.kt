package ceui.pixiv.ui.recommend

import androidx.annotation.StringRes
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.network.ShaftApiV2
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.NovelFeedItem
import timber.log.Timber

/**
 * shaft-api-v2 榜单接口的 `type` 枚举(服务端稳定字符串,不是展示文案,别本地化)。
 * 收藏榜 / AI 榜 / 全年龄榜 / 浏览量榜 / 标签专区 / 年代榜 的类型 tab 都用这三个值。
 */
object RankType {
    const val ILLUST = "illust"
    const val MANGA = "manga"
    const val NOVEL = "novel"

    /** 三类型 tab 的默认顺序:插画 / 漫画 / 小说。 */
    val ALL: List<String> = listOf(ILLUST, MANGA, NOVEL)

    /** 只有插画 / 漫画(AI 榜:novel 没有 illust_ai_type,带 ?ai 会被服务端 400)。 */
    val ILLUST_MANGA: List<String> = listOf(ILLUST, MANGA)

    /** tab 标题资源(同 FragmentRecentRecommend 的 type_illust/manga/novel)。 */
    @StringRes
    fun titleRes(type: String): Int = when (type) {
        MANGA -> R.string.type_manga
        NOVEL -> R.string.type_novel
        else -> R.string.type_illust
    }
}

/**
 * 榜单 item.bean → FeedItem 的共用映射(跑在 Default 线程、纯函数、零捕获)。
 * 收藏榜 / 浏览量榜 / 热度榜 的 item 形状都是 [ShaftApiV2.TrendingWorkItem],只有
 * 「热度 pill 显什么数」和「bean 解析成 Illust 还是 Novel」两处不同,这里把两条路统一:
 * - 插画 / 漫画:bean → loxia [Illust],装 trendingScore、清上报者收藏态,走 [IllustFeedItem.of]
 *   (含全局内容过滤);
 * - 小说:bean → loxia [Novel],清上报者收藏态,热度分单独带进 [NovelFeedItem]。
 */
internal fun ShaftApiV2.TrendingWorkItem.toIllustFeedItem(
    score: Float,
    skipAiFilter: Boolean = false,
    logTag: String = "RankFeed",
): IllustFeedItem? {
    val json = bean ?: return null
    val illust = try {
        Shaft.sGson.fromJson(json, Illust::class.java)
    } catch (e: Throwable) {
        Timber.tag(logTag).w(e, "skip malformed illust bean id=$target_id")
        return null
    } ?: return null
    // payload 里的收藏态是上报者的,清零让用户以自己名义收藏。
    return IllustFeedItem.of(
        illust.withTrendingScore(score).withBookmarked(false),
        skipAiFilter = skipAiFilter,
    )
}

internal fun ShaftApiV2.TrendingWorkItem.toNovelFeedItem(
    score: Float,
    logTag: String = "RankFeed",
): NovelFeedItem? {
    val json = bean ?: return null
    val novel = try {
        Shaft.sGson.fromJson(json, Novel::class.java)
    } catch (e: Throwable) {
        Timber.tag(logTag).w(e, "skip malformed novel bean id=$target_id")
        return null
    } ?: return null
    // 清上报者收藏态;热度分不是 Novel 的字段,单独带进 NovelFeedItem。
    return NovelFeedItem.of(novel.copy(is_bookmarked = false), score)
}

/** 按 [type] 选插画卡还是小说卡。 */
internal fun ShaftApiV2.TrendingWorkItem.toRankFeedItem(
    type: String,
    score: Float,
    skipAiFilter: Boolean = false,
    logTag: String = "RankFeed",
): FeedItem? = if (type == RankType.NOVEL) {
    toNovelFeedItem(score, logTag)
} else {
    toIllustFeedItem(score, skipAiFilter, logTag)
}
