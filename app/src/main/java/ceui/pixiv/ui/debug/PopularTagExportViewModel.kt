package ceui.pixiv.ui.debug

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.IllustResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.search.SortType
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「标签热度导出」debug 页的逻辑层(数据归 ViewModel,Fragment 只渲染)。
 *
 * 核心逻辑搬自 JCStaff 的 TagIllustSearchViewModel,但接 Pixiv-Shaft 自己的 API:
 *   输入一个 tag name → 以会员身份按 popular_desc(热度)搜索 → 跟着 next_url
 *   连续拉前 N 页 → 累计结果序列化成 JSON → 落盘成 .txt 到「下载/Shaft」(文件管理器可见)。
 *
 * 非会员账号 popular_desc 不可用,自动退回 popular_preview(单页预览,只有 1 页)。
 */
class PopularTagExportViewModel : ViewModel() {

    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    private val _log = MutableLiveData("")
    val log: LiveData<String> = _log

    private val logBuffer = StringBuilder()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** 导出文件内容:元信息 + 作品列表,整体是合法 JSON。 */
    private data class Envelope(
        val tag: String,
        val sort: String,
        val searchTarget: String,
        val requestedPages: Int,
        val fetchedPages: Int,
        val illustCount: Int,
        val exportedAt: String,
        val illusts: List<Illust>,
    )

    fun export(context: Context, tagName: String, pages: Int) {
        if (_running.value == true) return
        val appContext = context.applicationContext
        val safePages = pages.coerceIn(1, MAX_PAGES)
        _running.value = true
        logBuffer.setLength(0)
        _log.value = ""
        appendLog("开始:tag=「$tagName」,目标 $safePages 页")
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) { runExport(appContext, tagName, safePages) }
                appendLog(summary)
            } catch (e: Exception) {
                appendLog("✗ 失败:${e.javaClass.simpleName} ${e.message ?: ""}")
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun runExport(context: Context, tagName: String, pages: Int): String {
        val isPremium = SessionManager.isPremium
        val searchTarget = "partial_match_for_tags"
        // 会员 → popular_desc(真热度排序,可翻页);非会员 → popular_preview(单页预览)
        val sort = if (isPremium) SortType.POPULAR_DESC else SortType.POPULAR_PREVIEW
        appendLog(
            if (isPremium) "会员账号:按 popular_desc 热度排序"
            else "⚠ 非会员账号:popular_desc 不可用,退回 popular_preview(仅 1 页预览)"
        )

        val collected = mutableListOf<Illust>()

        val firstPage: IllustResponse = if (isPremium) {
            Client.appApi.searchIllustManga(
                word = tagName,
                sort = sort,
                search_target = searchTarget,
                merge_plain_keyword_results = true,
                include_translated_tag_results = true,
            )
        } else {
            Client.appApi.popularPreview(
                word = tagName,
                sort = sort,
                search_target = searchTarget,
                merge_plain_keyword_results = true,
                include_translated_tag_results = true,
            )
        }
        collected += firstPage.displayList
        var pagesFetched = 1
        var nextUrl = firstPage.nextPageUrl
        appendLog("第 1 页:+${firstPage.displayList.size}(累计 ${collected.size})")

        while (pagesFetched < pages && !nextUrl.isNullOrEmpty()) {
            delay(PAGE_DELAY_MS)
            val json = Client.appApi.generalGet(nextUrl).string()
            val resp = gson.fromJson(json, IllustResponse::class.java)
            collected += resp.displayList
            pagesFetched++
            nextUrl = resp.nextPageUrl
            appendLog("第 $pagesFetched 页:+${resp.displayList.size}(累计 ${collected.size})")
        }

        val envelope = Envelope(
            tag = tagName,
            sort = sort,
            searchTarget = searchTarget,
            requestedPages = pages,
            fetchedPages = pagesFetched,
            illustCount = collected.size,
            exportedAt = isoNow(),
            illusts = collected,
        )
        val displayName = buildFileName(tagName)
        val location = writeToDownloads(context, displayName, gson.toJson(envelope))
        return "✓ 完成:${collected.size} 作品 / $pagesFetched 页\n已保存:$location"
    }

    private fun buildFileName(tagName: String): String {
        val safeTag = tagName.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(40)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "pixiv_${safeTag}_popular_$ts.txt"
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    /**
     * 写「下载/Shaft」,文件管理器可见。
     * API 29+ 走 MediaStore.Downloads(无需权限);24-28 写公共 Download 目录
     * (依赖已声明的 WRITE_EXTERNAL_STORAGE,maxSdkVersion=28)。
     */
    private fun writeToDownloads(context: Context, displayName: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建 Downloads 记录")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开输出流")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Download/$SUB_DIR/$displayName"
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB_DIR
            ).apply { mkdirs() }
            val file = File(dir, displayName)
            FileOutputStream(file).use { it.write(bytes) }
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("text/plain"), null
            )
            file.absolutePath
        }
    }

    private fun appendLog(line: String) {
        if (logBuffer.isNotEmpty()) logBuffer.append('\n')
        logBuffer.append(line)
        _log.postValue(logBuffer.toString())
    }

    companion object {
        private const val MAX_PAGES = 30        // 热度搜索本身页数有限,30 页足够也防滥用
        private const val PAGE_DELAY_MS = 1200L // 翻页限速,别把账号打挂
        private const val SUB_DIR = "Shaft"      // 落在 Download/Shaft/
    }
}
