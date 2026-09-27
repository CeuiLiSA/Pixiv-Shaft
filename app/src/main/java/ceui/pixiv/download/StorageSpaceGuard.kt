package ceui.pixiv.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.text.format.Formatter
import ceui.lisa.R
import ceui.lisa.core.Manager
import ceui.pixiv.services.appServices
import com.hjq.toast.Toaster
import timber.log.Timber
import java.io.File

/**
 * 下载前的剩余空间闸门（pixez#1361：空间写满时继续下载，只会落出 0 字节 / 截断的坏图）。
 *
 * 空间不够时不是让每条任务各自失败——批量队列里几百页会刷出几百条「失败」，清完空间还得
 * 逐条重试——而是把单页下载（[Manager]）和批量队列一起**暂停**，提示一次。清理后在下载管理点
 * 「全部继续」即可原样接着下，staging 里已下的字节也还在。
 *
 * 两处入口：
 *   - [Manager] 派发每一页前查一次；传输失败时再按 [isOutOfSpace] / 余量复查——写到一半才满的情况。
 *   - 批量队列拉下一件新作品前查一次；动图不进 [Manager]，只能在这里拦。
 */
object StorageSpaceGuard {

    private const val TAG = "StorageSpaceGuard"

    /**
     * 低于这个余量就不再开新的下载。一张原图多在 1–30MB，staged 下载要先写 `.part`、提交时再拷一份
     * 到目标，峰值约两倍；剩下的留给数据库、MMKV 等 app 自身写入——磁盘真写满时它们也会失败。
     */
    private const val MIN_FREE_BYTES = 100L * 1024 * 1024

    /**
     * 多条并发任务几乎同时撞上时只提示一次。窗口要短：用户没清空间就点「全部继续」，会立刻
     * 再被暂停，这时得再提示一次，否则看起来像按钮没反应。
     */
    private const val NOTICE_DEBOUNCE_MS = 2_000L

    // lazy：[isOutOfSpace] 是纯逻辑，单测里加载本类不该碰 Looper
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 只在主线程读写，见 [pauseDownloadsForLowStorage]。 */
    private var lastNoticeAt = 0L

    /** 剩余空间是否还够开一条新下载。读不到空间信息时放行——宁可照常下，也不能因为查询失败把下载全拦死。 */
    @JvmStatic
    fun hasRoomForDownload(context: Context): Boolean {
        val free = freeBytes(context) ?: return true
        return free >= MIN_FREE_BYTES
    }

    /**
     * 各下载落点里最小的剩余空间；一个都读不到时返回 `null`。
     *
     * staged 下载先写 `cacheDir`（内部存储），MediaStore 目标在主外部存储——多数机型是同一块分区，
     * 但不保证，各查一次取小。SAF 选到 SD 卡时目标卷查不到（拿不到路径），至少 staging 这一段有保障。
     */
    private fun freeBytes(context: Context): Long? {
        return listOfNotNull(context.cacheDir, context.externalCacheDir)
            .mapNotNull { availableBytes(it) }
            .minOrNull()
    }

    /** 用 [StatFs] 而不是 [File.getUsableSpace]：后者路径无效时静默返回 0，会被误判成「已满」。 */
    private fun availableBytes(dir: File): Long? = try {
        StatFs(dir.path).availableBytes
    } catch (e: IllegalArgumentException) {
        Timber.tag(TAG).w(e, "statfs failed for %s", dir)
        null
    }

    /** 异常链里是否带着「设备空间不足」（ENOSPC）。写流、拷贝、提交各环节抛出的都会带上这段原文。 */
    @JvmStatic
    fun isOutOfSpace(throwable: Throwable?): Boolean {
        var t = throwable
        var depth = 0
        while (t != null && depth < 8) {
            val message = t.message
            if (message != null &&
                (message.contains("ENOSPC") || message.contains("No space left on device"))
            ) {
                return true
            }
            t = t.cause
            depth++
        }
        return false
    }

    /**
     * 暂停单页下载与批量队列，并提示用户清理空间。任意线程可调，统一切到主线程执行——
     * 与下载管理页「全部暂停」按钮同一套动作，恢复也走同一个「全部继续」。
     */
    @JvmStatic
    fun pauseDownloadsForLowStorage(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            Manager.get().stopAll()
            appContext.appServices().queueDownloadManager.pause()
            val now = SystemClock.elapsedRealtime()
            if (lastNoticeAt != 0L && now - lastNoticeAt < NOTICE_DEBOUNCE_MS) return@post
            lastNoticeAt = now
            val free = freeBytes(appContext)?.coerceAtLeast(0L) ?: 0L
            Timber.tag(TAG).w("paused all downloads, free=%d", free)
            Toaster.showLong(
                appContext.getString(
                    R.string.download_paused_low_storage,
                    Formatter.formatShortFileSize(appContext, free),
                )
            )
        }
    }
}
