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
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    fun resolveVariable(name: String, format: String?): String {
        val raw = when (name) {
            "id" -> meta.id.toString()
            "title" -> meta.title
            "page" -> (meta.page ?: 0).toString()
            "pages" -> meta.totalPages.toString()
            "ext" -> ext
            "author" -> meta.author.name
            "author_id" -> meta.author.id.toString()
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
        is Condition.PageGreaterThan -> meta.totalPages > condition.threshold
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
