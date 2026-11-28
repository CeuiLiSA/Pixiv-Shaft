package ceui.pixiv.ui.translate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.utils.Common
import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.api.model.MetaSinglePage
import ceui.pixiv.download.Downloads
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.ui.detail.DownloadFab
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 本地图片/漫画 OCR 翻译入口与会话辅助（#1406）。
 *
 * 将用户通过系统多图选择器挑出的 1..N 张本地图片包装成负数 ID 的合成 [Illust]，直接复用
 * [ImageDetailActivity] + [ceui.lisa.activities.ImageTranslationViewModel] +
 * [MangaBatchTranslateCenter] 整套单页/整部/圈选 OCR 翻译管线；译图保存跑在进程级协程中
 * 走 [DownloadsRegistry] 的 [Bucket.Illust] 通道，退出看图页照常写完，落到用户当前图片下载目录/相册并带 `_translated` 后缀。
 */
object LocalMangaViewer {

    const val DATA_TYPE = "本地漫画"
    const val ILLUST_TYPE = "local_manga"
    const val TRANSLATED_FILENAME_SUFFIX = "_translated"

    sealed interface SaveState {
        data object Saving : SaveState
        data object Done : SaveState
    }

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _saveStates = MutableStateFlow<Map<String, SaveState>>(emptyMap())
    val saveStates: StateFlow<Map<String, SaveState>> = _saveStates.asStateFlow()

    @JvmStatic
    fun createPickerIntent(context: Context): Intent =
        ActivityResultContracts.PickMultipleVisualMedia().createIntent(
            context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )

    @JvmStatic
    fun parsePickerResult(resultCode: Int, data: Intent?): List<Uri> =
        ActivityResultContracts.PickMultipleVisualMedia().parseResult(resultCode, data)

    @JvmStatic
    fun open(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val illust = buildIllust(
            uriStrings = uris.map { it.toString() },
            title = context.getString(R.string.local_manga_translate_entry),
        )
        context.startActivity(createIntent(context, illust, initialPage = 0))
    }

    fun isLocalManga(illust: Illust?): Boolean =
        illust != null && illust.id < 0L && illust.type == ILLUST_TYPE

    fun saveFabState(file: File): DownloadFab = when (_saveStates.value[file.path]) {
        SaveState.Saving -> DownloadFab.Downloading(0)
        SaveState.Done -> DownloadFab.Done
        null -> DownloadFab.Idle
    }

    fun isTranslationSaved(file: File): Boolean = _saveStates.value[file.path] == SaveState.Done

    internal fun clearSavedStateForTest() {
        _saveStates.value = emptyMap()
    }

    /**
     * 按所选 URI 序列派生稳定的非零负数 ID（与 [ceui.pixiv.ui.novel.local.LocalLibraryStore.novelIdFor]
     * 同方案）：永不与正数 Pixiv 作品 ID 冲突，同一批图重进还能命中 [MangaBatchTranslateCenter] 译图缓存。
     */
    fun sessionIdFor(uriStrings: List<String>): Long {
        var h = 1125899906842597L
        for (uri in uriStrings) {
            for (c in uri) h = 31 * h + c.code
            h = 31 * h + 1 // 分隔符，避免 ["ab","c"] 与 ["a","bc"] 撞同一个串
        }
        val v = h and Long.MAX_VALUE
        return -(v or 1L)
    }

    fun buildIllust(uriStrings: List<String>, title: String): Illust {
        require(uriStrings.isNotEmpty()) { "uriStrings must not be empty" }
        val firstUrls = allPointingTo(uriStrings[0])
        val isSingle = uriStrings.size == 1
        return Illust(
            id = sessionIdFor(uriStrings),
            title = title,
            type = ILLUST_TYPE,
            visible = true,
            page_count = uriStrings.size,
            image_urls = firstUrls,
            meta_single_page = if (isSingle) MetaSinglePage(original_image_url = uriStrings[0]) else null,
            meta_pages = if (isSingle) null else uriStrings.map { MetaPage(image_urls = allPointingTo(it)) },
        )
    }

    fun extractPageUris(illust: Illust): List<Uri> {
        val rawUrls = if (illust.page_count <= 1) {
            listOfNotNull(illust.meta_single_page?.original_image_url ?: illust.image_urls?.original)
        } else {
            illust.meta_pages.orEmpty().mapNotNull { it.image_urls?.original ?: it.image_urls?.large }
        }
        return rawUrls.filter { it.isNotBlank() }.map { Uri.parse(it) }
    }

    fun createIntent(context: Context, illust: Illust, initialPage: Int = 0): Intent {
        val pageCount = illust.page_count.coerceAtLeast(1)
        val intent = Intent(context, ImageDetailActivity::class.java).apply {
            putExtra("illust", illust)
            putExtra("dataType", DATA_TYPE)
            putExtra("index", initialPage.coerceIn(0, pageCount - 1))
        }
        val contentUris = extractPageUris(illust).filter { it.scheme == "content" }
        if (contentUris.isNotEmpty()) {
            val clipData = ClipData.newRawUri("local_manga", contentUris[0])
            for (i in 1 until contentUris.size) {
                clipData.addItem(ClipData.Item(contentUris[i]))
            }
            intent.clipData = clipData
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    /**
     * 进程级保存入口：点击保存后即刻退出看图页也能完整写完并弹出结果提示，不会因页面销毁触发
     * `onAbort()` 删掉半截译图（与 [ceui.pixiv.snapshot.SnapshotImageSaver] / [ceui.pixiv.download.ImagePageSaver] 同语义）。
     */
    @MainThread
    fun startSaveTranslatedPage(
        context: Context,
        translatedFile: File,
        sourceUriString: String?,
        pageIndex: Int,
        pageCount: Int,
    ): Boolean = startSaveTranslatedPage(
        context = context,
        translatedFile = translatedFile,
        sourceUriString = sourceUriString,
        pageIndex = pageIndex,
        pageCount = pageCount,
        downloads = DownloadsRegistry.downloads,
        config = DownloadsRegistry.store.loadOrFallback(),
        scope = saveScope,
        onToast = { Common.showToast(it) },
    )

    @MainThread
    internal fun startSaveTranslatedPage(
        context: Context,
        translatedFile: File,
        sourceUriString: String?,
        pageIndex: Int,
        pageCount: Int,
        downloads: Downloads,
        config: DownloadConfig,
        scope: CoroutineScope = saveScope,
        onToast: (String) -> Unit = { Common.showToast(it) },
    ): Boolean {
        val pathKey = translatedFile.path
        if (_saveStates.value[pathKey] == SaveState.Saving) return false
        val app = context.applicationContext
        _saveStates.update { it + (pathKey to SaveState.Saving) }
        scope.launch {
            var succeeded = false
            try {
                saveTranslatedPage(
                    context = app,
                    translatedFile = translatedFile,
                    sourceUriString = sourceUriString,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    downloads = downloads,
                    config = config,
                )
                succeeded = true
                onToast(app.getString(R.string.string_181))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.w(e, "[LocalManga] save translated page failed page=%d", pageIndex)
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                onToast(app.getString(R.string.save_image_failed, detail))
            } finally {
                _saveStates.update { current ->
                    if (succeeded) current + (pathKey to SaveState.Done) else current - pathKey
                }
            }
        }
        return true
    }

    /**
     * 将本地图片的译图 PNG 保存到用户当前图片下载目录/相册（遵循 [Bucket.Illust] 存储位置与冲突策略）。
     * 单页保留原图主名加 `_translated.png`，多页追加 `_p<N>_translated.png` 避免同名源文件撞名覆盖/误跳过。
     */
    internal suspend fun saveTranslatedPage(
        context: Context,
        translatedFile: File,
        sourceUriString: String?,
        pageIndex: Int,
        pageCount: Int = 1,
        downloads: Downloads = DownloadsRegistry.downloads,
        config: DownloadConfig = DownloadsRegistry.store.loadOrFallback(),
    ) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            val sourceName = queryDisplayName(app, sourceUriString)
            val displayName = buildTranslatedDisplayName(sourceName, pageIndex, pageCount)
            val destination = DownloadItems.illustGalleryDestination(displayName, config)
            val handle = downloads.openRaw(Bucket.Illust, destination, "image/png") ?: return@withContext
            var committed = false
            try {
                handle.stream.use { out ->
                    FileInputStream(translatedFile).use { input ->
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
        }
    }

    internal fun buildTranslatedDisplayName(
        sourceDisplayName: String?,
        pageIndex: Int,
        pageCount: Int = 1,
    ): String {
        val raw = sourceDisplayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringAfterLast(':')
            ?.trim()
            .orEmpty()
        val dot = raw.lastIndexOf('.')
        val parsedStem = when {
            raw.isEmpty() -> ""
            dot > 0 -> raw.substring(0, dot).trim()
            dot == 0 -> raw.substring(1).trim()
            else -> raw
        }
        val stem = if (parsedStem.isEmpty()) {
            "local_manga_p${pageIndex + 1}"
        } else {
            val root = parsedStem.removeSuffix(TRANSLATED_FILENAME_SUFFIX).ifEmpty { "local_manga" }
            if (pageCount > 1) "${root}_p${pageIndex + 1}" else root
        }
        return "$stem$TRANSLATED_FILENAME_SUFFIX.png"
    }

    private fun queryDisplayName(context: Context, sourceUriString: String?): String? {
        if (sourceUriString.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(sourceUriString) }.getOrNull() ?: return null
        if (uri.scheme == "content") {
            val queried = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            if (!queried.isNullOrBlank()) return queried
        }
        return uri.lastPathSegment
    }

    private fun allPointingTo(uri: String): ImageUrls = ImageUrls(
        url = uri,
        large = uri,
        medium = uri,
        original = uri,
        small = uri,
        square_medium = uri,
    )
}
