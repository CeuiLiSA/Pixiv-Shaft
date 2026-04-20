package ceui.pixiv.ui.common

import android.content.Context
import android.content.Intent
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.utils.Params

object ImageUrlViewer {

    const val DATA_TYPE_URL_SINGLE = "URL单图"

    fun open(ctx: Context, url: String, saveName: String? = null) {
        if (url.isBlank()) return
        val intent = Intent(ctx, ImageDetailActivity::class.java).apply {
            putExtra("dataType", DATA_TYPE_URL_SINGLE)
            putExtra(Params.URL, url)
            val cleaned = saveName?.let { sanitizeFileName(it) }
            if (!cleaned.isNullOrBlank()) putExtra(Params.TITLE, cleaned)
        }
        ctx.startActivity(intent)
    }

    private val invalidFsChars = Regex("[\\\\/:*?\"<>|\\s]+")

    private fun sanitizeFileName(raw: String): String =
        raw.replace(invalidFsChars, "_").trim('_').take(80)
}
