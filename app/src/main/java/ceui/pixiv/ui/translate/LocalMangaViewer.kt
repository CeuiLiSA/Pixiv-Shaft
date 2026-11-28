package ceui.pixiv.ui.translate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.api.model.MetaSinglePage
import ceui.pixiv.download.Downloads
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.Bucket
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地图片/漫画 OCR 翻译入口与会话辅助（#1406）。
 *
 * 将用户通过系统多图选择器挑出的 1..N 张本地图片包装成负数 ID 的合成 [Illust]，直接复用
 * [ImageDetailActivity] + [ceui.lisa.activities.ImageTranslationViewModel] +
 * [MangaBatchTranslateCenter] 整套单页/整部/圈选 OCR 翻译管线；译图保存走 [DownloadsRegistry]
 * 的 [Bucket.Illust] 通道，落到用户当前图片下载目录/相册并带 `_translated` 后缀。
 */
object LocalMangaViewer {

    const val DATA_TYPE = "本地漫画"
    const val ILLUST_TYPE = "local_manga"
    const val TRANSLATED_FILENAME_SUFFIX = "_translated"

    private val savedTranslatedPaths = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun createPickerContract(): ActivityResultContract<PickVisualMediaRequest, List<Uri>> =
        ActivityResultContracts.PickMultipleVisualMedia()

    @JvmStatic
    fun createPickerRequest(): PickVisualMediaRequest =
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

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

    fun isTranslationSaved(file: File): Boolean = file.path in savedTranslatedPaths

    internal fun clearSavedStateForTest() {
        savedTranslatedPaths.clear()
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
     * 将本地图片的译图 PNG 保存到用户当前图片下载目录/相册（遵循 [Bucket.Illust] 存储位置与冲突策略）。
     * 文件名保留原图主名并追加 `_translated.png`。
     */
    suspend fun saveTranslatedPage(
        context: Context,
        translatedFile: File,
        sourceUriString: String?,
        pageIndex: Int,
        downloads: Downloads = DownloadsRegistry.downloads,
        config: DownloadConfig = DownloadsRegistry.store.loadOrFallback(),
    ) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            val sourceName = queryDisplayName(app, sourceUriString)
            val displayName = buildTranslatedDisplayName(sourceName, pageIndex)
            val destination = DownloadItems.illustGalleryDestination(displayName, config)
            val handle = downloads.openRaw(Bucket.Illust, destination, "image/png")
            if (handle == null) {
                // OverwritePolicy.Skip 且目标已存在：按已保存处理（与 ImagePageSaver / SnapshotImageSaver 一致）
                savedTranslatedPaths.add(translatedFile.path)
                return@withContext
            }
            var committed = false
            try {
                handle.stream.use { out ->
                    FileInputStream(translatedFile).use { input ->
                        input.copyTo(out)
                    }
                }
                handle.onFinish()
                committed = true
                savedTranslatedPaths.add(translatedFile.path)
            } finally {
                if (!committed) {
                    runCatching { handle.stream.close() }
                    runCatching { handle.onAbort() }
                }
            }
        }
    }

    internal fun buildTranslatedDisplayName(sourceDisplayName: String?, pageIndex: Int): String {
        val raw = sourceDisplayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringAfterLast(':')
            ?.trim()
            .orEmpty()
        val dot = raw.lastIndexOf('.')
        val stem = when {
            raw.isEmpty() -> ""
            dot > 0 -> raw.substring(0, dot).trim()
            dot == 0 -> raw.substring(1).trim()
            else -> raw
        }.ifEmpty { "local_manga_p${pageIndex + 1}" }
        val finalStem = if (stem.endsWith(TRANSLATED_FILENAME_SUFFIX)) {
            stem
        } else {
            "$stem$TRANSLATED_FILENAME_SUFFIX"
        }
        return "$finalStem.png"
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
