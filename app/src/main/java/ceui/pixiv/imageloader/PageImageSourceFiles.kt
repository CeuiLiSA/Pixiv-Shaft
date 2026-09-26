package ceui.pixiv.imageloader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 把任意来源落成 [File] —— 取文件型消费方（AI 抠图 / 超分 / 翻译 / 设为壁纸 / 保存这一张）
 * 唯一需要认识的接口。
 *
 * - [PageImageSource.Local] 且有现成 `file` → 直接返回，**零拷贝**；
 * - [PageImageSource.Local] 但只有 uri（`content://`）→ 拷进 `cacheDir/page_src/`；
 * - [PageImageSource.Remote] → `task.awaitFile()`，语义与今天完全一致（Error 会 retry 一次，
 *   最终仍失败时抛异常，调用方照旧自己 catch，并记得把 `CancellationException` 重抛出去）；
 * - [PageImageSource.Unavailable] → null。
 *
 * **拷贝只发生在真正需要 `File` 的这一侧** —— 这正是渲染层能安心接入统一模型的原因：
 * 它拿的是 [PageImageSource.Local.renderModel]，全程零拷贝。
 */
suspend fun PageImageSource.awaitFile(context: Context): File? = when (this) {
    is PageImageSource.Local -> file ?: copyLocalUriToCache(context, uri)
    is PageImageSource.Remote -> task.awaitFile()
    PageImageSource.Unavailable -> null
}

/** 整批翻译一部长篇（172P × 2MB 级别）不能把每一页都留在 cache 里，只保留最近写入的若干份。 */
private const val MAX_CACHED_FILES = 8

private const val CACHE_DIR_NAME = "page_src"

private const val TAG = "PageImageSource"

/**
 * `content://` 这类只有可读流的来源，落一份到 cache 再交给需要 `File` 的消费方。
 *
 * 文件名按 uri 稳定派生：同一页反复 awaitFile 命中同一份，不会越积越多。目录按「最多留
 * [MAX_CACHED_FILES] 份」自限流 —— 比 WallpaperSetter.copyToShareCache 的「每次清空」更保守，
 * 因为这里会被整批翻译逐页调用，清空会把上一页刚拷好的文件删掉。
 */
private suspend fun copyLocalUriToCache(context: Context, uri: Uri): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
            val target = File(dir, cacheFileName(uri))
            if (target.isFile && target.length() > 0) return@runCatching target
            pruneCacheDir(dir)
            val source = context.contentResolver.openInputStream(uri) ?: return@runCatching null
            source.use { input -> target.outputStream().use { input.copyTo(it) } }
            target.takeIf { it.isFile && it.length() > 0 }
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "本地 uri 落文件失败 uri=%s", uri)
            null
        }
    }

private fun pruneCacheDir(dir: File) {
    val files = dir.listFiles()?.filter { it.isFile } ?: return
    if (files.size < MAX_CACHED_FILES) return
    files.sortedByDescending { it.lastModified() }
        .drop(MAX_CACHED_FILES - 1) // 给马上要写进去的这一份留一格
        .forEach { it.delete() }
}

private fun cacheFileName(uri: Uri): String {
    val ext = uri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.lowercase()
        ?.takeIf { segment -> segment.length in 2..4 && segment.all { it.isLetterOrDigit() } }
        ?: "img"
    return "page_%08x.%s".format(uri.toString().hashCode(), ext)
}