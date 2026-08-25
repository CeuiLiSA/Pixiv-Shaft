package ceui.pixiv.ui.novel.reader.paginate

import android.content.Context
import android.graphics.Typeface
import ceui.pixiv.ui.novel.reader.settings.PresetFonts
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches [Typeface] instances keyed by font id + weight + bold flag.
 *
 * 只认 [PresetFonts.BUILT_IN]。用户导入字体([ceui.lisa.database.NovelCustomFontDao] 那张表)
 * 目前没有任何 UI 写入,也没有任何调用方给本对象注册过解析器——之前那个全局可写的
 * `customFontResolver` 回调是一条从未接上的线,已删。将来真要做自定义字体,请让
 * [resolve] 显式接收一个 `(String) -> ReaderFont?`(或 [ReaderFont] 本身),不要再往
 * 进程级 object 里塞可变回调。
 */
object TypefaceProvider {
    private val cache = ConcurrentHashMap<String, Typeface>()

    fun resolve(context: Context, fontId: String, weight: Int, bold: Boolean): Typeface {
        val font = PresetFonts.BUILT_IN.firstOrNull { it.id == fontId }
            ?: PresetFonts.SYSTEM
        val cacheKey = "${font.id}|$weight|$bold"
        return cache.getOrPut(cacheKey) {
            val base = font.resolveTypeface(context)
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            if (style == Typeface.NORMAL) base else Typeface.create(base, style)
        }
    }
}
