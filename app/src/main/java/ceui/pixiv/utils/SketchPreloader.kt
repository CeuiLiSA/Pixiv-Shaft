package ceui.pixiv.utils

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.sketch
import timber.log.Timber
import java.io.File

/**
 * 把原图提前解码进 Sketch 内存缓存,让二级大图页([com.github.panpf.zoomimage.SketchZoomImageView])
 * 第一次打开就命中内存缓存、秒开不黑屏 —— 而不是等进了 C 才现解全分辨率大图(尤其大文件/大尺寸)导致黑屏。
 *
 * 命中前提是预加载请求与大图页 `image.loadImage(file/uri)` 产出**同一个 memory cache key**:
 * 大图页用 ViewSizeResolver(全屏 ≈ 屏幕尺寸),这里显式 size(屏幕宽高) 对齐;URI 与展示请求一致。
 * 用 applicationContext 避免请求挂在某个非 STARTED 的 Activity lifecycle 上等待。
 */
object SketchPreloader {

    @JvmStatic
    fun warm(context: Context, file: File) {
        if (!file.exists() || file.length() <= 0) return
        warmUri(context, Uri.fromFile(file))
    }

    /** 详情页已确认可读的本地下载 Uri，与大图页 loadImage(uri) 使用同一缓存键。 */
    @JvmStatic
    fun warmUri(context: Context, uri: Uri) {
        val appCtx = context.applicationContext
        val dm = appCtx.resources.displayMetrics
        val startMs = SystemClock.elapsedRealtime()
        val request = ImageRequest.Builder(appCtx, uri.toString())
            .size(dm.widthPixels, dm.heightPixels)
            .addListener(
                onSuccess = { _, result ->
                    Timber.d("[SketchPreload] READY ${uri.lastPathSegment} ms=${SystemClock.elapsedRealtime() - startMs} source=${result.dataFrom}")
                },
                onError = { _, result ->
                    Timber.w(result.throwable, "[SketchPreload] FAIL ${uri.lastPathSegment} ms=${SystemClock.elapsedRealtime() - startMs}")
                }
            )
            .build()
        appCtx.sketch.enqueue(request)
        Timber.d("[SketchPreload] warm ${uri.lastPathSegment} size=${dm.widthPixels}x${dm.heightPixels}")
    }
}
