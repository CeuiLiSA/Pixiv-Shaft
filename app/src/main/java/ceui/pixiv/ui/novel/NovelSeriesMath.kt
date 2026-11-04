package ceui.pixiv.ui.novel

import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 小说系列页的几条纯计算（无 Android 依赖，JVM 单测见 NovelSeriesMathTest）。
 */

/**
 * 系列封面。`/v2/novel/series` 的系列详情里没有封面字段，但各篇的封面路径会说明来源：
 * 用了作者给系列设的封面时是 `…/sci<系列ID>_…`，自己单独设了封面时是 `…/ci<作品ID>_…`
 * （实测 series 11962368 的 46 篇全是 `sci11962368_…`）。有系列封面就用它，否则退回
 * [coverUrls] 里的第一张 —— 调用方按「第 1 篇 → 最新一篇 → 其余」的顺序传入。
 */
fun pickSeriesCoverUrl(seriesId: Long, coverUrls: List<String?>): String? {
    val urls = coverUrls.filter { !it.isNullOrEmpty() }.map { it!! }
    return urls.firstOrNull { it.contains("/sci${seriesId}_") } ?: urls.firstOrNull()
}

/** 系列总阅读时长的展示单位：不到一小时按分钟，否则四舍五入到小时。 */
sealed interface SeriesReadingDuration {
    data class Minutes(val value: Int) : SeriesReadingDuration
    data class Hours(val value: Int) : SeriesReadingDuration
}

/** 口径同单篇（[novelReadingMinutes]，每分钟 500 字）；字数未知或为 0 时返回 null。 */
fun seriesReadingDuration(totalCharacters: Int): SeriesReadingDuration? {
    val minutes = novelReadingMinutes(totalCharacters) ?: return null
    return if (minutes < 60) {
        SeriesReadingDuration.Minutes(minutes)
    } else {
        SeriesReadingDuration.Hours(max(1, (minutes / 60.0).roundToInt()))
    }
}

/** 一篇章节的阅读进度快照：[index] 是系列里的 1-based 位置，[percent] 同 [novelReadingPercent]。 */
data class SeriesChapterProgress(
    val novelId: Long,
    val index: Int,
    val percent: Int?,
    val lastReadAt: Long,
)

/** 系列页主按钮要去的那一篇；[index] 为 null 表示知道是哪篇、但还不知道它是第几篇。 */
data class SeriesReadTarget(val novelId: Long, val index: Int?)

/**
 * 系列页主按钮该去哪一篇，null = 这个系列还没读过（显示「从第 1 篇开始」）。
 *
 * - 阅读器记下过本系列最后打开的那篇（[recordedNovelId]）就以它为准。超长系列里它可能还在没翻到的
 *   那几页，[chapters] 里找不到 —— 照样去那一篇，只是标不出第几篇；
 * - 旧版本没记过：退回在已知章节里找最近读过的那篇；
 * - 那一篇已经读完（100%）且下一篇已知，直接指向下一篇。
 */
fun seriesReadTarget(chapters: List<SeriesChapterProgress>, recordedNovelId: Long?): SeriesReadTarget? {
    val anchor = if (recordedNovelId != null && recordedNovelId > 0) {
        chapters.firstOrNull { it.novelId == recordedNovelId }
            ?: return SeriesReadTarget(recordedNovelId, null)
    } else {
        chapters.filter { it.percent != null }.maxByOrNull { it.lastReadAt } ?: return null
    }
    if (anchor.percent == 100) {
        chapters.firstOrNull { it.index == anchor.index + 1 }
            ?.let { return SeriesReadTarget(it.novelId, it.index) }
    }
    return SeriesReadTarget(anchor.novelId, anchor.index)
}
