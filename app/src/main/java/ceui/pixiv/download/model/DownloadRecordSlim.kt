package ceui.pixiv.download.model

import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.loxia.ImageUrls
import ceui.loxia.Novel
import ceui.loxia.Series
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.api.model.MetaSinglePage
import com.google.gson.Gson
import timber.log.Timber

/**
 * 下载记录元数据瘦身工具。
 *
 * 在持久化到下载记录表时，对作品的 JSON 负载进行定向轻量化：
 * 1. 彻底移除长文案 caption、tools 等非展示必需字段；
 * 2. 针对多图作品的 meta_pages 采用基于“/”目录级的公共路径前缀压缩：
 *    - p0 保留完整 URL 作为绝对基准路径锚点；
 *    - p1..pN 仅保留最后一个“/”后的相对文件名，消除长路径重复；
 *    - 无论文件名规则如何变化，均可基于真实上级目录完整还原；
 * 3. 仅保留列表卡片展示、作者主页跳转、动图/类型判定、下载文件改名模板及搜索所必需的核心属性。
 */
object DownloadRecordSlim {

    private const val TAG = "DownloadRecordSlim"

    private val gson: Gson
        get() = Shaft.sGson ?: fallbackGson

    private val fallbackGson: Gson by lazy { Gson() }

    /**
     * 从完整 URL 中提取以最后一个“/”结尾的公共目录前缀。
     */
    @JvmStatic
    fun extractBaseDir(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val lastSlash = url.lastIndexOf('/')
        return if (lastSlash >= 0) url.substring(0, lastSlash + 1) else null
    }

    /**
     * 将可能被压缩为相对文件名的字符串还原为完整 URL。
     * 若已是 http:// 或 https:// 开头的完整链接，则原样返回。
     */
    @JvmStatic
    fun resolveUrl(raw: String?, baseDir: String?): String? {
        if (raw.isNullOrEmpty()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw
        }
        return if (baseDir != null) "$baseDir$raw" else raw
    }

    /**
     * 将插画/漫画数据对象精简为下载记录所需的最小模型。
     */
    @JvmStatic
    fun toSlim(illust: Illust): Illust {
        val p0Original = illust.meta_single_page?.original_image_url
            ?: illust.meta_pages?.firstOrNull()?.image_urls?.original

        val baseDir = extractBaseDir(p0Original)

        val slimPages = illust.meta_pages?.mapIndexed { index, page ->
            val orig = page.image_urls?.original
            val relOrig = if (index == 0 || baseDir == null || orig == null) {
                orig
            } else if (orig.startsWith(baseDir)) {
                orig.substring(baseDir.length)
            } else {
                orig
            }
            MetaPage(
                image_urls = if (index == 0) {
                    page.image_urls
                } else {
                    relOrig?.let { ImageUrls(original = it) }
                }
            )
        }

        return Illust(
            id = illust.id,
            title = illust.title,
            type = illust.type,
            page_count = illust.page_count,
            create_date = illust.create_date,
            width = illust.width,
            height = illust.height,
            x_restrict = illust.x_restrict,
            illust_ai_type = illust.illust_ai_type,
            user = illust.user?.let { User(id = it.id, name = it.name) },
            image_urls = illust.image_urls?.let {
                ImageUrls(
                    square_medium = it.square_medium,
                    medium = it.medium,
                    large = it.large,
                )
            },
            meta_single_page = p0Original?.let { MetaSinglePage(original_image_url = it) },
            meta_pages = slimPages,
            tags = illust.tags?.mapNotNull { it.name }?.map { Tag(name = it) },
            caption = null,
            tools = null,
            series = null,
            restrict = null,
            sanity_level = null,
            total_bookmarks = null,
            total_view = null,
        )
    }

    /**
     * 解析作品的所有原图直链（自动处理公共目录前缀的还原）。
     */
    @JvmStatic
    fun resolveOriginalUrls(illust: Illust): List<String> {
        val pages = illust.meta_pages
        if (!pages.isNullOrEmpty()) {
            val p0Url = pages.firstOrNull()?.image_urls?.original
                ?: illust.meta_single_page?.original_image_url
            val baseDir = extractBaseDir(p0Url)
            return pages.mapNotNull { page ->
                resolveUrl(page.image_urls?.original, baseDir)?.takeIf(String::isNotEmpty)
            }
        }
        val single = illust.meta_single_page?.original_image_url
        if (!single.isNullOrEmpty()) return listOf(single)
        val fallback = illust.image_urls?.original
        return if (!fallback.isNullOrEmpty()) listOf(fallback) else emptyList()
    }

    /**
     * 将小说数据对象精简为下载记录所需的最小模型。
     */
    @JvmStatic
    fun toSlim(novel: Novel): Novel {
        return Novel(
            id = novel.id,
            title = novel.title,
            user = novel.user?.let { User(id = it.id, name = it.name) },
            image_urls = novel.image_urls?.let {
                ImageUrls(
                    square_medium = it.square_medium,
                    medium = it.medium,
                    large = it.large,
                )
            },
            page_count = novel.page_count,
            create_date = novel.create_date,
            x_restrict = novel.x_restrict,
            novel_ai_type = novel.novel_ai_type,
            tags = novel.tags?.mapNotNull { it.name }?.map { Tag(name = it) },
            caption = null,
            series = novel.series?.let { Series(id = it.id, title = it.title) },
        )
    }

    /**
     * 序列化精简后的插画/漫画 JSON。
     */
    @JvmStatic
    fun createIllustJson(illust: Illust?): String {
        if (illust == null) return ""
        return runCatching {
            gson.toJson(toSlim(illust))
        }.getOrElse { ex ->
            Timber.tag(TAG).w(ex, "Failed to serialize slim Illust id=${illust.id}")
            gson.toJson(illust)
        }
    }

    /**
     * 序列化精简后的小说 JSON。
     */
    @JvmStatic
    fun createNovelJson(novel: Novel?): String {
        if (novel == null) return ""
        return runCatching {
            gson.toJson(toSlim(novel))
        }.getOrElse { ex ->
            Timber.tag(TAG).w(ex, "Failed to serialize slim Novel id=${novel.id}")
            gson.toJson(novel)
        }
    }

    /**
     * 对现有的原始 JSON 字符串进行解析并精简重写。
     */
    @JvmStatic
    fun slimIllustJson(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return rawJson
        if ("\"shaft_imported\":true" in rawJson) return rawJson

        return runCatching {
            val parsed = gson.fromJson(rawJson, Illust::class.java)
            if (parsed != null && parsed.id > 0) {
                gson.toJson(toSlim(parsed))
            } else {
                rawJson
            }
        }.getOrElse {
            rawJson
        }
    }

    /**
     * 对通用的下载记录（插画或小说）JSON 进行解析与精简重写。
     */
    @JvmStatic
    fun slimDownloadJson(fileName: String?, rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return rawJson
        if ("\"shaft_imported\":true" in rawJson) return rawJson

        if (fileName != null && Params.NOVEL_KEY in fileName) {
            return runCatching {
                val parsed = gson.fromJson(rawJson, Novel::class.java)
                if (parsed != null && parsed.id > 0) {
                    gson.toJson(toSlim(parsed))
                } else {
                    rawJson
                }
            }.getOrElse { rawJson }
        }

        return slimIllustJson(rawJson)
    }
}
