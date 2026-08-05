package ceui.pixiv.download.template

import ceui.pixiv.download.model.Flag
import ceui.pixiv.download.model.ItemMeta
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Variable + condition lookup used during rendering. Values returned by
 * [resolveVariable] are pre-scrubbed of `/` and `\` so directory separators in the
 * final string come from template literals only.
 */
class TemplateContext(
    val meta: ItemMeta,
    val ext: String,
    private val numbering: PageNumbering = PageNumbering(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    fun resolveVariable(name: String, format: String?): String {
        val pageOffset = if (numbering.indexFrom1) 1 else 0
        val raw = when (name) {
            "id" -> meta.id.toString()
            "title" -> meta.title
            "page" -> pageNumber((meta.page ?: 0) + pageOffset, format)
            // 兼容别名：4.5.x 旧模板里直接写 {page1}（永远 1-based）。e18080ab 把它合并到
            // {page} + 全局 pageIndexFrom1，但用户 DownloadConfig 里已持久化的旧字符串
            // 仍带 {page1}，这里保留以免渲染抛 Unknown variable。语义保持原样：始终 +1。
            "page1" -> pageNumber((meta.page ?: 0) + 1, format)
            "pages" -> meta.totalPages.toString()
            "ext" -> ext
            "author" -> meta.author.name
            "author_id" -> meta.author.id.toString()
            // 系列变量（issue #964）：不在系列中 / 来源拿不到时渲染成空串，
            // RelativePath.parse 会把由此产生的空目录段折叠掉。
            "series" -> meta.seriesTitle.orEmpty()
            "series_order" -> meta.seriesOrder?.let { orderNumber(it, format) }.orEmpty()
            // 系列章节数：合并下载（Bucket.NovelSeries）拿的是这一份合集里实际写进去
            // 的章节数，单篇下载拿的是所在系列的总篇数；两者都拿不到时渲染成空串。
            "chapters" -> meta.seriesTotal?.toString().orEmpty()
            "w" -> meta.width?.toString().orEmpty()
            "h" -> meta.height?.toString().orEmpty()
            "created" -> formatInstant(format)
            else -> error("Unknown variable '{$name}'")
        }
        return scrubSeparators(raw)
    }

    fun evaluate(condition: Condition): Boolean = when (condition) {
        is Condition.Flag -> {
            val flag = Flag.entries.firstOrNull { it.name.equals(condition.name, ignoreCase = true) }
                ?: error("Unknown flag '${condition.name}' in condition")
            val present = meta.has(flag)
            if (condition.negated) !present else present
        }
        is Condition.PageGreaterThan -> {
            val result = meta.totalPages > condition.threshold
            if (condition.negated) !result else result
        }
    }

    /**
     * `{page}` / `{page1}` → digits, zero-padded per [PageNumbering.padded] or
     * per an explicit `{page:000}` mask (the mask always wins, so a template can
     * pin a width regardless of the global switch).
     */
    private fun pageNumber(value: Int, format: String?): String =
        value.toString().padStart(explicitWidth(format) ?: autoWidth(), '0')

    /**
     * `{series_order}` — same shape as [pageNumber]: an explicit `{series_order:00}`
     * mask wins; otherwise the global padding switch pads to the series' total
     * chapter count so chapters sort as text (floor 2, mirroring [autoWidth]).
     */
    private fun orderNumber(value: Int, format: String?): String =
        value.toString().padStart(explicitWidth(format) ?: seriesOrderAutoWidth(), '0')

    private fun seriesOrderAutoWidth(): Int {
        if (!numbering.padded) return 1
        val highest = maxOf(meta.seriesTotal ?: 1, meta.seriesOrder ?: 1)
        return maxOf(2, highest.toString().length)
    }

    /**
     * `{page:000}` — width is the number of zeros.
     *
     * Deliberately lenient: an unrecognized mask falls back to [autoWidth]
     * rather than throwing. Before page masks existed `{page:anything}` parsed
     * fine and ignored the format, so a persisted template carrying one must
     * keep working — throwing here would send [SafeTemplateRender] down its
     * fallback path and silently replace the user's whole naming scheme with
     * the bucket default. Typos are caught at save time instead, by
     * [TemplateValidator.checkPageMasks].
     */
    private fun explicitWidth(format: String?): Int? {
        if (format.isNullOrEmpty()) return null
        if (!isPageMask(format)) return null
        return format.length
    }

    /**
     * Enough digits for this work's highest page number, so every page of the
     * work shares one width and sorts numerically as text. Floored at 2 because
     * most works have 2-9 pages — without the floor the switch would visibly do
     * nothing for them and read as broken.
     */
    private fun autoWidth(): Int {
        if (!numbering.padded) return 1
        val highest = meta.totalPages - 1 + (if (numbering.indexFrom1) 1 else 0)
        return maxOf(2, highest.coerceAtLeast(1).toString().length)
    }

    private fun formatInstant(pattern: String?): String {
        val p = pattern ?: DEFAULT_DATE_FORMAT
        val formatter = getFormatter(p).withZone(zoneId)
        return formatter.format(meta.createdAt)
    }

    private fun scrubSeparators(s: String): String =
        s.replace('/', '_').replace('\\', '_')

    companion object {
        private const val DEFAULT_DATE_FORMAT = "yyyyMMdd_HHmmss"
        const val MAX_PAGE_WIDTH = 6

        /** A well-formed `{page:…}` width mask: 1..[MAX_PAGE_WIDTH] zeros. */
        fun isPageMask(format: String): Boolean =
            format.isNotEmpty() && format.length <= MAX_PAGE_WIDTH && format.all { it == '0' }
        private const val FORMATTER_CACHE_LIMIT = 32

        // Compiling a pattern is ~μs-order but every rendered filename hits it,
        // so cache. Keyed by pattern string — formatters are thread-safe.
        // Capped to defend against a pathological config that keeps generating
        // new patterns; we evict the whole cache on overflow, which is fine
        // because rebuild is cheap.
        private val FORMATTER_CACHE = ConcurrentHashMap<String, DateTimeFormatter>()

        private fun getFormatter(pattern: String): DateTimeFormatter {
            FORMATTER_CACHE[pattern]?.let { return it }
            if (FORMATTER_CACHE.size >= FORMATTER_CACHE_LIMIT) FORMATTER_CACHE.clear()
            val built = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
            FORMATTER_CACHE[pattern] = built
            return built
        }
    }
}
