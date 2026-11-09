package ceui.pixiv.ui.upscale

import android.content.Context
import java.io.File
import java.io.IOException

/** Only generated image files owned by the AI features; model files and downloaded images are excluded. */
object AiResultStorage {
    data class Category(val count: Int, val bytes: Long)

    data class Stats(
        val upscale: Category,
        val rembg: Category,
        val manga: Category,
    ) {
        val totalCount: Int get() = upscale.count + rembg.count + manga.count
        val totalBytes: Long get() = upscale.bytes + rembg.bytes + manga.bytes
    }

    private val mangaFileName = Regex("""manga_translated_p\d+_\d+\.png""")

    private fun files(dir: File, matches: (File) -> Boolean): List<File> {
        if (!dir.exists()) return emptyList()
        val children = dir.listFiles() ?: throw IOException("Cannot list AI result directory: ${dir.path}")
        return children.filter { it.isFile && matches(it) }
    }

    private fun resultFiles(context: Context): Triple<List<File>, List<File>, List<File>> =
        Triple(
            files(File(context.filesDir, "upscale-cache")) { it.extension.equals("png", true) },
            files(File(context.filesDir, "rembg-cache")) { it.extension.equals("png", true) },
            files(context.cacheDir) { mangaFileName.matches(it.name) },
        )

    private fun stats(files: List<File>) = Category(files.size, files.sumOf { it.length() })

    @JvmStatic
    fun scan(context: Context): Stats {
        val (upscale, rembg, manga) = resultFiles(context.applicationContext)
        return Stats(stats(upscale), stats(rembg), stats(manga))
    }

    /** Caller must hold the AI and manga task cleanup gates while this runs on a worker thread. */
    @JvmStatic
    fun clear(context: Context, includeUpscale: Boolean, includeRembg: Boolean, includeManga: Boolean): Boolean {
        val app = context.applicationContext
        val selected = mutableListOf<File>()
        if (includeUpscale) selected += files(File(app.filesDir, "upscale-cache")) {
            it.extension.equals("png", true)
        }
        if (includeRembg) selected += files(File(app.filesDir, "rembg-cache")) {
            it.extension.equals("png", true)
        }
        if (includeManga) selected += files(app.cacheDir) { mangaFileName.matches(it.name) }
        var complete = true
        for (file in selected) {
            if (!file.delete() && file.exists()) complete = false
        }
        return complete
    }
}
