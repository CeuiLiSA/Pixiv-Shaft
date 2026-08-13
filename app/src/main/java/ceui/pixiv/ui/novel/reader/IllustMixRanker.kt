package ceui.pixiv.ui.novel.reader

import ceui.loxia.Illust
import ceui.loxia.Novel
import kotlin.random.Random

/**
 * 「自动混排插画」的相关性排序（issue #999 相关性回补）：取材池只负责拿候选，
 * 这里决定消费顺序——[ceui.pixiv.ui.novel.reader.paginate.IllustMixInserter]
 * 按给定顺序插图，排在前面的先上屏。
 *
 * 打分：插画与小说的标签每重叠一个计 2 分，关注画师 +1 分——同等重叠下关注
 * 画师优先，零重叠时关注画师也仍排在陌生画师前面。标签按 name/translated_name
 * 双向、忽略大小写匹配。先按 [seed]（传 novelId）洗牌再稳定排序：同分候选的
 * 相对顺序对同一篇小说固定（重排版不跳图），不同小说各不相同。
 */
object IllustMixRanker {

    fun rank(illusts: List<Illust>, novel: Novel?, seed: Long): List<Illust> {
        if (illusts.size <= 1) return illusts
        val novelTags = novel?.tags.orEmpty()
            .flatMap { listOfNotNull(it.name, it.translated_name) }
            .mapNotNull { name -> name.trim().lowercase().takeIf { it.isNotEmpty() } }
            .toHashSet()
        return illusts.shuffled(Random(seed)).sortedByDescending { score(it, novelTags) }
    }

    private fun score(illust: Illust, novelTags: Set<String>): Int {
        val overlap = if (novelTags.isEmpty()) 0 else illust.tags.orEmpty().count { tag ->
            sequenceOf(tag.name, tag.translated_name)
                .mapNotNull { it?.trim()?.lowercase() }
                .any { it in novelTags }
        }
        val followed = if (illust.user?.is_followed == true) 1 else 0
        return overlap * 2 + followed
    }
}
