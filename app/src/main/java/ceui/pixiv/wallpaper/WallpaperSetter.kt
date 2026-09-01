package ceui.pixiv.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import ceui.lisa.R
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.pixiv.imageloader.ImageLoaderV3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 「设为壁纸」(issue #898 / #515):作品详情页与全屏看图页共用。
 *
 * 优先交给系统裁剪器([WallpaperManager.getCropAndSetWallpaperIntent]),用户在系统 UI
 * 里选主屏/锁屏并裁切 —— 这是官方推荐路径,也是唯一能让用户自己决定裁切位置的方式。
 * 某些 ROM / 受管设备没有裁剪器(抛 IllegalArgumentException / ActivityNotFound),
 * 退回 [WallpaperManager.setStream] 直接同时铺到主屏与锁屏(`allowBackup=true`,
 * 系统按屏幕比例居中裁切)。
 *
 * 图片文件复用 [ImageLoaderV3] 的共享加载任务:大图页已加载就直接取,不重复下载。
 */
object WallpaperSetter {

    private const val CACHE_DIR = "wallpaper_share"

    /**
     * 必须在主线程调用(内部自己切 IO)。全部失败路径都已 toast,调用方无需再处理。
     */
    suspend fun setFromIllust(activity: Activity, illust: Illust, pageIndex: Int) {
        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
        if (imageUrl == null) {
            Timber.w("[Wallpaper] original url missing id=%d page=%d", illust.id, pageIndex)
            Common.showToast(R.string.string_set_wallpaper_failed)
            return
        }
        val file = awaitLoadedFile(imageUrl)
        if (file == null) {
            Common.showToast(R.string.string_set_wallpaper_failed)
            return
        }
        val uri = runCatching {
            withContext(Dispatchers.IO) { copyToShareCache(activity, file, imageUrl) }
        }.getOrElse { ex ->
            Timber.w(ex, "[Wallpaper] prepare uri failed id=%d page=%d", illust.id, pageIndex)
            Common.showToast(R.string.string_set_wallpaper_failed)
            return
        }
        applyUri(activity, uri)
    }

    private suspend fun awaitLoadedFile(imageUrl: String): File? =
        try {
            ImageLoaderV3.obtain(imageUrl).awaitFile()
        } catch (e: CancellationException) {
            // 页面销毁导致协程取消:重抛,别把「取消」当成加载失败弹 toast
            throw e
        } catch (e: Exception) {
            Timber.w(e, "[Wallpaper] load image failed url=%s", imageUrl)
            null
        }

    private fun copyToShareCache(activity: Activity, source: File, imageUrl: String): Uri {
        val dir = File(activity.cacheDir, CACHE_DIR).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        // 扩展名跟着原图走(jpg/png),系统裁剪器按 MIME 嗅探,名字对上更稳。
        val ext = imageUrl.substringAfterLast('.', "jpg").substringBefore('?').take(4)
        val target = File(dir, "wallpaper_from_shaft.$ext")
        source.inputStream().use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(activity, "${activity.packageName}.provider", target)
    }

    private suspend fun applyUri(activity: Activity, uri: Uri) {
        val manager = WallpaperManager.getInstance(activity)
        if (!manager.isSetWallpaperAllowed) {
            // 受管配置 / 儿童模式禁止改壁纸:系统裁剪器也会拒,直接说清楚。
            Common.showToast(R.string.string_set_wallpaper_not_allowed)
            return
        }
        val cropIntent = try {
            manager.getCropAndSetWallpaperIntent(uri).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: IllegalArgumentException) {
            // 系统没有裁剪器组件
            Timber.i("[Wallpaper] no system cropper, fallback to setStream")
            null
        }
        if (cropIntent != null) {
            try {
                activity.startActivity(cropIntent)
                return
            } catch (e: Exception) {
                Timber.w(e, "[Wallpaper] launch cropper failed, fallback to setStream")
            }
        }
        val ok = withContext(Dispatchers.IO) {
            try {
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    manager.setStream(
                        stream,
                        null,
                        true,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                    )
                } != null
            } catch (e: Exception) {
                Timber.w(e, "[Wallpaper] setStream failed")
                false
            }
        }
        Common.showToast(
            if (ok) R.string.string_set_wallpaper_done else R.string.string_set_wallpaper_failed
        )
    }
}
