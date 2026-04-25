package ceui.pixiv.download.template

import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.model.RelativePath

/**
 * A compiled, reusable template. Construct via [compile] — parsing happens once,
 * rendering is cheap.
 */
class Template private constructor(
    val source: String,
    private val nodes: List<TemplateNode>,
) {

    fun render(meta: ItemMeta, ext: String, pageIndexFrom1: Boolean = DownloadsRegistry.store.loadOrFallback().pageIndexFrom1): RelativePath {
        val out = StringBuilder(source.length + 32)
        val ctx = TemplateContext(meta, ext, pageIndexFrom1)
        nodes.forEach { it.render(ctx, out) }
        return RelativePath.parse(out.toString())
    }

    override fun toString(): String = "Template($source)"

    companion object {
        fun compile(source: String): Template {
            val parsed = TemplateParser(source).parseAll()
            return Template(source, parsed)
        }
    }
}
