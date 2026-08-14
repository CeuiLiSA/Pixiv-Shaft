package ceui.pixiv.ui.pinned

import ceui.lisa.activities.Shaft
import ceui.lisa.database.SearchEntity
import ceui.lisa.models.IllustsBean
import ceui.loxia.Illust
import ceui.loxia.ImageUrls
import ceui.loxia.Tag
import ceui.pixiv.feeds.FeedItem
import timber.log.Timber

/**
 * 「侧边栏 → 置顶标签」列表里一条 item（feeds 框架条目）。
 *
 * 数据来源：[ceui.lisa.utils.PixivOperate.insertPinnedSearchHistory] 写入的 `search_table`
 * 行，里面 `previewIllustsJson` 是 `{tag, resp:{illusts:[…]}}` 这种 shape 的 JSON
 * （由 [ceui.pixiv.utils.buildPinnedTagPreviewJson] 写入，沿用旧 Prime 内置榜 assets 的
 * `{tag, resp:{illusts}}` 外形）。
 *
 * 字段命名 / 暴露形式刻意对齐 [ceui.pixiv.ui.prime.PrimeTagItemHolder] —— 这样 cell xml 就能
 * 一比一抄 `cell_item_prime_tag.xml` 的 binding 表达式。
 *
 * [SearchEntity] 是无 equals 的可变 Java POJO，[FeedItem] 的默认（引用）相等性照实反映——
 * 每次重查 DB 都是全新实例，靠 [feedKey] 做身份去重即可，不需要为它另造 equals。
 */
class PinnedTagItemHolder(val entity: SearchEntity) : FeedItem {

    override val feedKey: Any get() = entity.id

    private val parsed: PinnedTagPreviewParsed? = parsePreview(entity.previewIllustsJson)

    /** 标签元信息 —— 优先从 `previewIllustsJson` 里那份完整 TagsBean 取（翻译名齐全），
     *  缺时退回到 `keyword` 字符串本身（旧数据 / 3 参 insertPinnedSearchHistory 路径）。*/
    val tag: Tag = Tag(
        name = parsed?.tagName ?: entity.keyword.orEmpty(),
        translated_name = parsed?.tagTranslated,
    )

    val illust0: Illust? = parsed?.previewUrls?.getOrNull(0)?.toPreviewIllust()
    val illust1: Illust? = parsed?.previewUrls?.getOrNull(1)?.toPreviewIllust()
    val illust2: Illust? = parsed?.previewUrls?.getOrNull(2)?.toPreviewIllust()

    val hasPreview: Boolean = listOfNotNull(illust0, illust1, illust2).isNotEmpty()

    /** translated_name 有内容时，name 才显示在副标题；否则上面那行已经是 name，副标题隐藏避免重复。*/
    val showSubtitle: Boolean = !parsed?.tagTranslated.isNullOrBlank() &&
        parsed?.tagTranslated != tag.name
}

// ── previewIllustsJson 解析 ──

private data class PinnedTagPreviewParsed(
    val tagName: String?,
    val tagTranslated: String?,
    val previewUrls: List<String>,
)

private fun parsePreview(json: String?): PinnedTagPreviewParsed? {
    if (json.isNullOrBlank()) return null
    return try {
        val root = Shaft.sGson.fromJson(json, PreviewRoot::class.java) ?: return null
        val urls = root.resp?.illusts.orEmpty().mapNotNull { it.image_urls?.square_medium }
        PinnedTagPreviewParsed(
            tagName = root.tag?.name,
            tagTranslated = root.tag?.translated_name,
            previewUrls = urls,
        )
    } catch (t: Throwable) {
        Timber.w(t, "Failed to parse pinned tag preview JSON")
        null
    }
}

/**
 * 只解我们关心的子集。直接 `IllustResponse` / `TagsBean` 也行，但那些类字段多、还可能跟 Room
 * 自动迁移产生不必要的耦合，保持本地 POJO 更稳。
 */
private data class PreviewRoot(val tag: PreviewTag?, val resp: PreviewResp?)
private data class PreviewTag(val name: String?, val translated_name: String?)
private data class PreviewResp(val illusts: List<IllustsBean>?)

private fun String.toPreviewIllust(): Illust =
    Illust(id = 0, image_urls = ImageUrls(square_medium = this))
