package ceui.pixiv.ui.novel.reader.paginate

import android.content.Context
import android.graphics.Typeface
import ceui.pixiv.ui.novel.reader.settings.PresetFonts
import ceui.pixiv.ui.novel.reader.settings.ReaderWebFont
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches [Typeface] instances keyed by font id + weight + bold flag.
 *
 * 认两类字体：[PresetFonts.BUILT_IN] 的系统字体，和下载到本地的 [ReaderWebFont]。
 * 下载字体还没装好（或本机不支持）时回退系统字体，且**不缓存这次回退**，装好后下一次排版
 * 就能拿到真字体；下载 / 删除完成后由 [ceui.pixiv.ui.novel.reader.settings.ReaderFontRepository]
 * 调 [evict] 清掉旧条目。
 *
 * 用户导入字体([ceui.lisa.database.NovelCustomFontDao] 那张表)目前没有任何 UI 写入。
 * 将来真要做，请让 [resolve] 显式接收一个 `(String) -> ReaderFont?`(或 [ReaderFont] 本身),
 * 不要再往进程级 object 里塞可变回调。
 */
object TypefaceProvider {
    private val cache = ConcurrentHashMap<String, Typeface>()

    fun resolve(context: Context, fontId: String, weight: Int, bold: Boolean): Typeface {
        ReaderWebFont.byId(fontId)?.let { web ->
            val cacheKey = "${web.id}|$weight|$bold"
            cache[cacheKey]?.let { return it }
            val base = web.loadTypeface(context, weight)
                ?: return resolve(context, PresetFonts.SYSTEM.id, weight, bold)
            return cache.getOrPut(cacheKey) { withStyle(base, bold) }
        }
        val font = PresetFonts.BUILT_IN.firstOrNull { it.id == fontId }
            ?: PresetFonts.SYSTEM
        val cacheKey = "${font.id}|$weight|$bold"
        return cache.getOrPut(cacheKey) { withStyle(font.resolveTypeface(context), bold) }
    }

    fun evict(fontId: String) {
        cache.keys.removeIf { it.startsWith("$fontId|") }
    }

    private fun withStyle(base: Typeface, bold: Boolean): Typeface =
        if (bold) Typeface.create(base, Typeface.BOLD) else base
}
