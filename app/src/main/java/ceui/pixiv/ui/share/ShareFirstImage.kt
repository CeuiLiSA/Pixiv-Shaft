package ceui.pixiv.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.download.IllustDownload
import ceui.loxia.Illust
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

// 分享首图（多 P 也只发 p0，与菜单文案 R.string.string_454 一致）。
// 用 Glide.asFile() 取磁盘缓存的原始字节，不经过 Bitmap 解码 / PNG 再压缩 ——
// 既避开原图尺寸下 .asBitmap() 的 OOM 风险，也保留原始格式与画质。
fun Fragment.shareFirstImage(illust: Illust) {
    val ctx = context ?: return
    val url = IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_ORIGINAL) ?: return

    viewLifecycleOwner.lifecycleScope.launch {
        val uri = runCatching {
            withContext(Dispatchers.IO) {
                val cached = Glide.with(ctx.applicationContext)
                    .asFile()
                    .load(GlideUrlChild(url))
                    .submit()
                    .get()
                copyToShareCache(ctx, cached, illust.id, url)
            }
        }.onFailure { Timber.e(it, "[shareFirstImage] failed illustId=${illust.id}") }
            .getOrNull() ?: return@launch

        val mime = ctx.contentResolver.getType(uri) ?: "image/*"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, mime)
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
    }
}

private fun copyToShareCache(ctx: Context, source: File, illustId: Long, url: String): Uri {
    val ext = url.substringAfterLast('/')
        .substringAfterLast('.', "jpg")
        .substringBefore('?')
    val dir = File(ctx.externalCacheDir, "images").apply { mkdirs() }
    val target = File(dir, "${illustId}_p0.$ext")
    source.inputStream().use { input ->
        target.outputStream().use { input.copyTo(it) }
    }
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", target)
}
