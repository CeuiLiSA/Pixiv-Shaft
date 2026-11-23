package ceui.pixiv.snapshot

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Common
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.Downloads
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.ExifKeywordWriter
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.DownloadItem
import ceui.pixiv.ui.bulk.UgoiraEngine
import ceui.pixiv.ui.bulk.UgoiraVideoEncoder
import ceui.pixiv.ui.bulk.encodeFramesToGif
import ceui.pixiv.ui.detail.DownloadFab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

private val KNOWN_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private const val TRANSLATED_FILENAME_SUFFIX = "_translated"

/**
 * 识别快照内单张图片文件的真实扩展名，用于构造写盘文件名与 MIME：
 *
 * 1. 先按文件头魔数判断真实格式 —— 非原图快照存的是 large JPEG，但原 illust.json 的 original URL
 *    可能是 `.png`，且早期/无后缀 URL 在快照里会落成 `.img`（MediaStore 会拒收 `image/img`）；
 * 2. 魔数未命中（如 HEIF 或极小测试文件）时再退回文件自身扩展名；
 * 3. 仍无法识别时兜底 `"jpg"`。
 */
internal fun resolveSnapshotImageExtension(file: File): String {
    val header = runCatching {
        FileInputStream(file).use { input ->
            val buf = ByteArray(12)
            val read = input.read(buf)
            if (read > 0) buf.copyOf(read) else ByteArray(0)
        }
    }.getOrDefault(ByteArray(0))

    sniffImageExtension(header)?.let { return it }

    val ext = file.extension.lowercase(Locale.US)
    if (ext in KNOWN_IMAGE_EXTENSIONS) return ext
    return "jpg"
}

private fun sniffImageExtension(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() &&
        bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() &&
        bytes[7] == 0x0A.toByte()
    ) {
        return "png"
    }
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()
    ) {
        return "jpg"
    }
    if (bytes.size >= 4 &&
        bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte()
    ) {
        return "gif"
    }
    if (bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() &&
        bytes[11] == 'P'.code.toByte()
    ) {
        return "webp"
    }
    if (bytes.size >= 2 &&
        bytes[0] == 'B'.code.toByte() &&
        bytes[1] == 'M'.code.toByte()
    ) {
        return "bmp"
    }
    return null
}

/**
 * 为快照的某一页构造 [DownloadItem]：完整沿用 [Bucket.Illust] 与用户的命名模板/目录配置，
 * 扩展名与 MIME 强制对齐快照实际存档的文件字节，不依赖可能已失效或分辨率不一致的远程 URL。
 */
internal fun buildSnapshotPageDownloadItem(
    illust: Illust,
    pageIndex: Int,
    file: File,
): DownloadItem {
    val ext = resolveSnapshotImageExtension(file)
    return DownloadItems.illustPage(
        illust = illust,
        pageIndex = pageIndex,
        sourceUrl = "snapshot_p$pageIndex.$ext",
    )
}

/**
 * 通过统一下载 facade 将本地文件 [source] 写入用户存储（MediaStore 相册 / SAF 目录）。
 *
 * 返回写入后的 [Uri]；若用户配置了 Skip 覆盖策略且目标文件已存在，返回 `null`（视为已保存）。
 * 严格遵守 [ceui.pixiv.download.backend.StorageBackend.WriteHandle] 的 commit / abort 契约。
 */
internal fun writeFileToDownloads(
    downloads: Downloads,
    item: DownloadItem,
    source: File,
    filenameSuffix: String? = null,
): Uri? {
    val handle = if (filenameSuffix != null) {
        downloads.openDerived(item, filenameSuffix)
    } else {
        downloads.open(item)
    } ?: return null

    var committed = false
    try {
        handle.stream.use { out ->
            FileInputStream(source).use { input ->
                input.copyTo(out)
            }
        }
        handle.onFinish()
        committed = true
    } finally {
        if (!committed) {
            runCatching { handle.stream.close() }
            runCatching { handle.onAbort() }
        }
    }
    return handle.uri
}

internal data class SnapshotSavePageOutcome(
    val written: Boolean,
    val uri: Uri?,
)

internal data class SnapshotBatchSaveOutcome(
    val total: Int,
    val savedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val firstError: Throwable?,
) {
    val allSucceeded: Boolean get() = failedCount == 0 && total > 0
}

internal fun saveSnapshotStaticPage(
    context: Context,
    downloads: Downloads,
    data: SnapshotViewerData,
    pageIndex: Int,
    writeExif: Boolean = Shaft.sSettings?.isWriteTagsToImageExif() == true,
): SnapshotSavePageOutcome {
    val file = data.pageFile(pageIndex)
        ?: throw SnapshotException("snapshot page file missing: page=$pageIndex")
    val item = buildSnapshotPageDownloadItem(data.illust, pageIndex, file)
    val uri = writeFileToDownloads(downloads, item, file)
    if (uri != null && writeExif) {
        runCatching {
            ExifKeywordWriter.writeIfEnabled(
                context,
                uri,
                downloads.resolvePath(item).filename,
                data.illust.tags.orEmpty().mapNotNull { it?.name },
            )
        }
    }
    return SnapshotSavePageOutcome(written = uri != null, uri = uri)
}

internal fun saveAllSnapshotStaticPages(
    context: Context,
    downloads: Downloads,
    data: SnapshotViewerData,
    writeExif: Boolean = Shaft.sSettings?.isWriteTagsToImageExif() == true,
    onProgress: (Int) -> Unit = {},
): SnapshotBatchSaveOutcome {
    val total = data.illust.page_count.coerceAtLeast(1)
    var savedCount = 0
    var skippedCount = 0
    var failedCount = 0
    var firstError: Throwable? = null

    for (page in 0 until total) {
        try {
            val outcome = saveSnapshotStaticPage(context, downloads, data, page, writeExif)
            if (outcome.written) savedCount++ else skippedCount++
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            failedCount++
            if (firstError == null) firstError = t
            Timber.w(t, "[SnapshotSave] save page failed id=%s page=%d", data.manifest.snapshotId, page)
        }
        onProgress(((page + 1) * 100) / total)
    }

    return SnapshotBatchSaveOutcome(
        total = total,
        savedCount = savedCount,
        skippedCount = skippedCount,
        failedCount = failedCount,
        firstError = firstError,
    )
}

/**
 * 离线快照图片导出到相册 / 用户下载目录的执行器与进程级状态源（#1395）。
 *
 * 与在线下载（[ceui.pixiv.download.ImagePageSaver] / [ceui.lisa.download.IllustDownload]）的关键区别：
 * - **纯本地**：只从快照目录读取已归档的图片 / 动图帧，绝不发起任何网络请求；
 * - **不触发下载后自动收藏**：离线快照对 Pixiv 服务端保持只读；
 * - **不写 `illust_download_table`**：快照可能只归档了非原图（large），写进下载记录表会让在线详情页
 *   的 [ceui.pixiv.download.DownloadedPageIndex] 误把低清快照副本复用成原图；
 * - **完全遵循用户下载存储配置**：走 [DownloadsRegistry.downloads]，默认写入相册（MediaStore Images），
 *   用户自选的 SAF 目录、文件命名模板、覆盖策略与动图导出格式（MP4/GIF）全部生效。
 */
object SnapshotImageSaver {

    sealed interface SaveKey {
        val snapshotId: String

        data class All(override val snapshotId: String) : SaveKey
        data class Page(override val snapshotId: String, val page: Int) : SaveKey
        data class Translated(
            override val snapshotId: String,
            val page: Int,
            val filePath: String,
        ) : SaveKey
    }

    sealed interface SaveState {
        data class Saving(val percent: Int) : SaveState
        data object Done : SaveState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _states = MutableStateFlow<Map<SaveKey, SaveState>>(emptyMap())
    val states: StateFlow<Map<SaveKey, SaveState>> = _states.asStateFlow()

    fun allFabState(snapshotId: String): DownloadFab =
        when (val state = _states.value[SaveKey.All(snapshotId)]) {
            is SaveState.Saving -> DownloadFab.Downloading(state.percent)
            SaveState.Done -> DownloadFab.Done
            null -> DownloadFab.Idle
        }

    fun pageFabState(snapshotId: String, page: Int): DownloadFab =
        computePageFabState(_states.value, snapshotId, page)

    fun translatedFabState(snapshotId: String, page: Int, file: File): DownloadFab =
        when (val state = _states.value[SaveKey.Translated(snapshotId, page, file.path)]) {
            is SaveState.Saving -> DownloadFab.Downloading(state.percent)
            SaveState.Done -> DownloadFab.Done
            null -> DownloadFab.Idle
        }

    /** 保存快照的指定单页（供快照大图页使用）。 */
    @MainThread
    fun savePage(context: Context, snapshotId: String, isAuto: Boolean, page: Int): Boolean {
        val key = SaveKey.Page(snapshotId, page)
        val current = _states.value
        if (current[key] is SaveState.Saving || current[SaveKey.All(snapshotId)] is SaveState.Saving) {
            return false
        }
        val app = context.applicationContext
        _states.update { it + (key to SaveState.Saving(0)) }
        scope.launch {
            var succeeded = false
            var isSinglePage = false
            try {
                isSinglePage = withContext(Dispatchers.IO) {
                    val data = loadViewerData(app, snapshotId, isAuto)
                    saveSnapshotStaticPage(app, DownloadsRegistry.downloads, data, page)
                    data.illust.page_count <= 1
                }
                succeeded = true
                Common.showToast(R.string.string_181)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "[SnapshotSave] savePage failed id=%s page=%d", snapshotId, page)
                toastImageFailure(app, t)
            } finally {
                _states.update { map ->
                    reducePageSaveFinished(map, snapshotId, page, succeeded, isSinglePage)
                }
            }
        }
        return true
    }

    /** 保存快照的整部作品（静图全部页面，或动图导出为 MP4/GIF）。供详情页及动图大图页使用。 */
    @MainThread
    fun saveAll(context: Context, snapshotId: String, isAuto: Boolean): Boolean {
        val key = SaveKey.All(snapshotId)
        val busy = _states.value.any { (k, v) ->
            k.snapshotId == snapshotId && v is SaveState.Saving && (k is SaveKey.All || k is SaveKey.Page)
        }
        if (busy) return false
        val app = context.applicationContext
        _states.update { it + (key to SaveState.Saving(0)) }
        scope.launch {
            var succeeded = false
            var isUgoiraExport = false
            try {
                val data = withContext(Dispatchers.IO) { loadViewerData(app, snapshotId, isAuto) }
                isUgoiraExport = data.illust.isGif() && !data.ugoiraFrames?.files.isNullOrEmpty()
                if (isUgoiraExport) {
                    val written = saveUgoira(app, data) { pct -> publishProgress(key, pct) }
                    succeeded = true
                    Common.showToast(if (written) R.string.save_gif_success else R.string.save_gif_exists)
                } else {
                    val outcome = withContext(Dispatchers.IO) {
                        saveAllSnapshotStaticPages(
                            context = app,
                            downloads = DownloadsRegistry.downloads,
                            data = data,
                            onProgress = { pct -> publishProgress(key, pct) },
                        )
                    }
                    if (outcome.allSucceeded) {
                        succeeded = true
                        Common.showToast(R.string.string_181)
                    } else {
                        val detail = outcome.firstError?.let { errorDetail(it) }
                            ?: "${outcome.failedCount}/${outcome.total}"
                        Common.showToast(app.getString(R.string.save_image_failed, detail))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "[SnapshotSave] saveAll failed id=%s", snapshotId)
                if (isUgoiraExport) {
                    Common.showToast(app.getString(R.string.save_gif_failed, errorDetail(t)))
                } else {
                    toastImageFailure(app, t)
                }
            } finally {
                _states.update { map ->
                    if (succeeded) map + (key to SaveState.Done) else map - key
                }
            }
        }
        return true
    }

    /** 保存快照大图页中当前显示的译图（命名与在线大图页保持同一 `_translated` 后缀）。 */
    @MainThread
    fun saveTranslated(
        context: Context,
        snapshotId: String,
        illust: Illust,
        page: Int,
        translatedFile: File,
    ): Boolean {
        val key = SaveKey.Translated(snapshotId, page, translatedFile.path)
        if (_states.value[key] is SaveState.Saving) return false
        val app = context.applicationContext
        _states.update { it + (key to SaveState.Saving(0)) }
        scope.launch {
            var succeeded = false
            try {
                withContext(Dispatchers.IO) {
                    writeFileToDownloads(
                        downloads = DownloadsRegistry.downloads,
                        item = DownloadItems.illustTranslated(illust, page),
                        source = translatedFile,
                        filenameSuffix = TRANSLATED_FILENAME_SUFFIX,
                    )
                }
                succeeded = true
                Common.showToast(R.string.string_181)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "[SnapshotSave] saveTranslated failed id=%s page=%d", snapshotId, page)
                toastImageFailure(app, t)
            } finally {
                _states.update { map ->
                    if (succeeded) map + (key to SaveState.Done) else map - key
                }
            }
        }
        return true
    }

    private fun publishProgress(key: SaveKey, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _states.update { states ->
            val current = states[key]
            if (current is SaveState.Saving && current.percent != clamped) {
                states + (key to SaveState.Saving(clamped))
            } else {
                states
            }
        }
    }

    private fun loadViewerData(app: Context, snapshotId: String, isAuto: Boolean): SnapshotViewerData {
        SnapshotRuntimeCache.get(snapshotId)?.let { return it }
        val loaded = if (isAuto) {
            AutoSnapshotRepository.loadAutoViewerData(app, snapshotId)
        } else {
            SnapshotRepository.loadViewerData(app, snapshotId)
        }
        SnapshotRuntimeCache.put(snapshotId, loaded)
        return loaded
    }

    /**
     * 从快照自带的离线帧序列导出动图，遵循「动图保存格式」设置：
     * - MP4：优先直接复用快照内已压好的 `video.mp4`（零重编码）；没有则在缓存目录现压（不修改快照目录）；
     * - GIF（或设备不支持硬件编码时降级）：在缓存目录现编临时 GIF；
     * 临时文件在 `finally` 中一律清理。返回 true 表示新写入，false 表示 Skip 策略下已存在。
     */
    private suspend fun saveUgoira(
        app: Context,
        data: SnapshotViewerData,
        onProgress: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val frames = data.ugoiraFrames ?: error("ugoira frames missing")
        val wantMp4 = Shaft.sSettings?.isUgoiraSaveAsMp4() == true
        val tempDir = File(app.cacheDir, "snapshot_ugoira_export").apply { mkdirs() }
        var exportFile: File? = null
        var asVideo = false
        var temporary = false

        try {
            if (wantMp4) {
                val existingVideo = frames.video?.takeIf { it.isFile && it.length() > 0L }
                if (existingVideo != null) {
                    exportFile = existingVideo
                    asVideo = true
                    temporary = false
                } else if (!UgoiraVideoEncoder.deviceUnsupported) {
                    val tmpMp4 = File(tempDir, "${data.manifest.snapshotId}.mp4")
                    val encoded = UgoiraEngine.withVideoEncoder {
                        UgoiraVideoEncoder.encode(tmpMp4, frames.files, frames.delaysMs, onProgress)
                    }
                    if (encoded != null) {
                        exportFile = encoded
                        asVideo = true
                        temporary = true
                    } else {
                        runCatching { tmpMp4.delete() }
                    }
                }
            }

            if (exportFile == null) {
                val tmpGif = File(tempDir, "${data.manifest.snapshotId}.gif")
                try {
                    BufferedOutputStream(FileOutputStream(tmpGif)).use { bos ->
                        encodeFramesToGif(frames.files, frames.delaysMs, bos) { pct ->
                            coroutineContext.ensureActive()
                            onProgress(pct)
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { tmpGif.delete() }
                    throw t
                }
                exportFile = tmpGif
                asVideo = false
                temporary = true
            }

            val file = exportFile
            val uri = writeFileToDownloads(
                downloads = DownloadsRegistry.downloads,
                item = DownloadItems.ugoira(data.illust, asVideo),
                source = file,
            )
            uri != null
        } finally {
            if (temporary) {
                runCatching { exportFile?.delete() }
            }
        }
    }

    private fun toastImageFailure(app: Context, error: Throwable) {
        Common.showToast(app.getString(R.string.save_image_failed, errorDetail(error)))
    }

    private fun errorDetail(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}

internal fun computePageFabState(
    states: Map<SnapshotImageSaver.SaveKey, SnapshotImageSaver.SaveState>,
    snapshotId: String,
    page: Int,
): DownloadFab {
    val pageState = states[SnapshotImageSaver.SaveKey.Page(snapshotId, page)]
    if (pageState is SnapshotImageSaver.SaveState.Saving) return DownloadFab.Downloading(pageState.percent)
    val allState = states[SnapshotImageSaver.SaveKey.All(snapshotId)]
    if (allState is SnapshotImageSaver.SaveState.Saving) return DownloadFab.Downloading(allState.percent)
    if (pageState == SnapshotImageSaver.SaveState.Done || allState == SnapshotImageSaver.SaveState.Done) {
        return DownloadFab.Done
    }
    return DownloadFab.Idle
}

internal fun reducePageSaveFinished(
    states: Map<SnapshotImageSaver.SaveKey, SnapshotImageSaver.SaveState>,
    snapshotId: String,
    page: Int,
    succeeded: Boolean,
    isSinglePage: Boolean,
): Map<SnapshotImageSaver.SaveKey, SnapshotImageSaver.SaveState> {
    val pageKey = SnapshotImageSaver.SaveKey.Page(snapshotId, page)
    val allKey = SnapshotImageSaver.SaveKey.All(snapshotId)
    return if (succeeded) {
        val updated = states + (pageKey to SnapshotImageSaver.SaveState.Done)
        if (isSinglePage) updated + (allKey to SnapshotImageSaver.SaveState.Done) else updated
    } else {
        // 本页刚保存失败，不能让先前记录的 All=Done 继续把本页按钮染回「已保存」绿勾。
        val cleared = states - pageKey
        if (cleared[allKey] == SnapshotImageSaver.SaveState.Done) cleared - allKey else cleared
    }
}
