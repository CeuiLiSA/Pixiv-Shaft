package ceui.pixiv.cache

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.file.LegacyFile
import ceui.pixiv.download.StageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 原图专用磁盘缓存管理：负责原图落盘提交、LRU 淘汰与孤儿分片清扫。
 */
object OriginalImageCacheManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun getCacheDir(context: Context): File = LegacyFile.originalImageCacheFolder(context)

    @JvmStatic
    fun getStagingDir(context: Context): File = LegacyFile.originalStagingFolder(context)

    @JvmStatic
    fun keyForUrl(url: String): String = StageStore.keyForUrl(url)

    /**
     * 查询指定原图 URL 是否已缓存在本地。若命中则更新访问时间（LRU）并返回文件。
     */
    @JvmStatic
    fun find(context: Context, url: String): File? {
        val key = keyForUrl(url)
        val cacheDir = getCacheDir(context)
        val ext = fileExtensionForUrl(url)
        val directFile = File(cacheDir, "$key.$ext")
        if (directFile.isFile && directFile.length() > 0) {
            directFile.setLastModified(System.currentTimeMillis())
            return directFile
        }

        val datFile = File(cacheDir, "$key.dat")
        if (datFile.isFile && datFile.length() > 0) {
            datFile.setLastModified(System.currentTimeMillis())
            return datFile
        }

        val matched = cacheDir.listFiles { _, name -> name.startsWith(key) }
            ?.firstOrNull { it.isFile && it.length() > 0 }
        if (matched != null) {
            matched.setLastModified(System.currentTimeMillis())
            return matched
        }

        return null
    }

    /**
     * 将临时分片文件提交到原图缓存目录。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun commit(context: Context, url: String, partFile: File): File {
        val key = keyForUrl(url)
        val cacheDir = getCacheDir(context)
        val ext = fileExtensionForUrl(url)
        val targetFile = File(cacheDir, "$key.$ext")

        if (targetFile.exists()) {
            targetFile.delete()
        }

        val renamed = partFile.renameTo(targetFile)
        if (!renamed) {
            partFile.copyTo(targetFile, overwrite = true)
            partFile.delete()
        }

        targetFile.setLastModified(System.currentTimeMillis())

        val stagingDir = getStagingDir(context)
        StageStore.clear(stagingDir, key)

        trimAsync(context)

        return targetFile
    }

    /**
     * 同步按当前配额执行 LRU 淘汰。
     */
    @JvmStatic
    fun trimToQuota(
        context: Context,
        maxMb: Int = Shaft.sSettings?.originalImageCacheMaxMb ?: OriginalImageCacheQuota.DEFAULT_LIMIT_MB,
    ) {
        val maxBytes = OriginalImageCacheQuota.maxBytesForLimit(maxMb)
        val cacheDir = getCacheDir(context)
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return

        var totalSize = files.sumOf { it.length() }
        if (totalSize <= maxBytes) return

        val sorted = files.sortedBy { it.lastModified() }
        var evictedCount = 0
        for (file in sorted) {
            val length = file.length()
            if (file.delete()) {
                totalSize -= length
                evictedCount++
            }
            if (totalSize <= maxBytes) break
        }

        Timber.d("[OriginalCache] LRU trimmed $evictedCount files, remaining size: $totalSize bytes, quota: $maxBytes bytes")
    }

    /**
     * 异步触发 LRU 淘汰检查。
     */
    @JvmStatic
    fun trimAsync(
        context: Context,
        maxMb: Int = Shaft.sSettings?.originalImageCacheMaxMb ?: OriginalImageCacheQuota.DEFAULT_LIMIT_MB,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                trimToQuota(appContext, maxMb)
            } catch (e: Throwable) {
                Timber.w(e, "[OriginalCache] async trim failed")
            }
        }
    }

    /**
     * 清理超过指定时间未活动的孤儿分片（.part 与 .part.meta）。
     */
    @JvmStatic
    fun sweepOrphans(context: Context, maxAgeMs: Long = 48 * 3600 * 1000L): Int {
        val stagingDir = getStagingDir(context)
        val swept = StageStore.sweepOrphans(stagingDir, maxAgeMs, System.currentTimeMillis())
        if (swept > 0) {
            Timber.d("[OriginalCache] swept $swept orphaned staging files")
        }
        return swept
    }

    /**
     * 冷启动后台任务：清理孤儿临时分片并按配额清理超出大小的原图缓存。
     */
    @JvmStatic
    fun sweepAndTrim(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                sweepOrphans(appContext)
                trimToQuota(appContext)
            } catch (e: Throwable) {
                Timber.w(e, "[OriginalCache] sweepAndTrim failed")
            }
        }
    }

    /**
     * 清空全部原图缓存与临时分片。
     */
    @JvmStatic
    fun clearAll(context: Context) {
        val cacheDir = getCacheDir(context)
        val stagingDir = getStagingDir(context)
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        stagingDir.listFiles()?.forEach { runCatching { it.delete() } }
        Timber.d("[OriginalCache] clearAll finished")
    }

    /**
     * 统计当前原图缓存占用的总字节数。
     */
    @JvmStatic
    fun totalSizeBytes(context: Context): Long {
        val cacheDir = getCacheDir(context)
        return cacheDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    private fun fileExtensionForUrl(url: String): String {
        val cleaned = url.substringBefore('?').substringBefore('#')
        val ext = cleaned.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && ext.length in 2..5) ext else "dat"
    }
}
