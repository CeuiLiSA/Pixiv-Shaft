package ceui.pixiv.ui.novel.reader.paginate

import android.content.Context
import android.graphics.Typeface
import ceui.pixiv.ui.novel.reader.settings.PresetFonts
import ceui.pixiv.ui.novel.reader.settings.ReaderFont
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches [Typeface] instances keyed by font id + weight + bold flag.
 *
 * Custom fonts (user-imported .ttf/.otf) are supplied via [setCustomFontResolver];
 * the reader wires the DAO-backed resolver at Fragment start so cache invalidation
 * stays centralized here.
 */
object TypefaceProvider {
    private val cache = ConcurrentHashMap<String, Typeface>()

    @Volatile
    private var customFontResolver: ((String) -> ReaderFont?)? = null

    fun setCustomFontResolver(resolver: ((String) -> ReaderFont?)?) {
        customFontResolver = resolver
    }

    fun resolve(context: Context, fontId: String, weight: Int, bold: Boolean): Typeface {
        val font = PresetFonts.BUILT_IN.firstOrNull { it.id == fontId }
            ?: customFontResolver?.invoke(fontId)
            ?: PresetFonts.SYSTEM
        val cacheKey = "${font.id}|$weight|$bold"
        return cache.getOrPut(cacheKey) {
            val base = font.resolveTypeface(context)
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            if (style == Typeface.NORMAL) base else Typeface.create(base, style)
        }
    }

    fun evict(fontId: String) {
        val prefix = "$fontId|"
        cache.keys.toList().filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    fun clear() {
        cache.clear()
    }
}
