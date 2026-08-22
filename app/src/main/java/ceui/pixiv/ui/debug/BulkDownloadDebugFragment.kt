package ceui.pixiv.ui.debug

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.pixiv.events.EventReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次性 disk + DB 占用诊断页 —— 用来验证「3 GB 用户数据是不是批量下载的 staging
 * 图片漏掉了」这类假设。打开即异步扫:
 *
 *   1. App 私有目录大小:`/data/data/<pkg>/{databases, files, files/mmkv, cache}/`
 *      跟 `/sdcard/Android/data/<pkg>/{cache, files}/`。Android Settings 把
 *      cacheDir/externalCacheDir 归为「缓存」,其余归为「用户数据」—— 报告里两边
 *      都列,直接对得上系统设置的数字。
 *   2. Room 表行数 + `illustGson` 列总字节:`illust_download_table`(每个下载页
 *      一条) 跟 `download_queue` (每个 enqueue illust 一条) 都把整段 Illust
 *      JSON 当字符串存,这是 3 GB 的主要嫌疑犯,直接查总长就能定论。
 *   3. `cache/staging_dl/` 残留:批量下载写盘前先 stream 到本地 stage 文件,commit
 *      到 MediaStore/SAF 后 `stageFile.delete()`(Manager.java:812)。如果有泄漏
 *      应该在这里出现,且会算到「缓存」不是「用户数据」。
 *   4. MMKV 文件:`api-cache` 这种 ResponseStore 把 API JSON 缓在 MMKV,位置在
 *      `files/mmkv/`,算「用户数据」—— 第二嫌疑犯。
 *
 * 顶上一行 device fingerprint,跟 [ceui.pixiv.ui.recommend.FragmentEventHistory]
 * 那个复制 client_id 入口同语义,方便交叉对照后端事件流。
 */
class BulkDownloadDebugFragment : Fragment(R.layout.fragment_bulk_download_debug) {

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private lateinit var outputText: TextView
    private lateinit var outputScroll: NestedScrollView
    private lateinit var fingerprintText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { activity?.finish() }

        outputText = view.findViewById(R.id.outputText)
        outputScroll = view.findViewById(R.id.outputScroll)
        fingerprintText = view.findViewById(R.id.fingerprintText)

        // 整行点击都复制 —— 64 字符 hex 太长,用户单独点 TextView 选中再复制太繁琐。
        // 跟 FragmentEventHistory 的 action_copy_client_id 一致,toast 只回显前 12 位
        // 让用户确认是哪个 client。EventReporter.init 没跑完时 cid 是空串,这时给
        // not_ready toast 而不是复制空串。
        view.findViewById<LinearLayout>(R.id.fingerprintRow).setOnClickListener {
            val cid = EventReporter.currentClientId()
            if (cid.isEmpty()) {
                Common.showToast(getString(R.string.event_history_client_id_not_ready))
            } else {
                ClipBoardUtils.putTextIntoClipboard(requireContext(), cid, false)
                Common.showToast(getString(R.string.event_history_client_id_copied, cid.take(12)))
            }
        }
        refreshFingerprint()

        view.findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            refreshFingerprint()
            runDiag()
        }
        view.findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = outputText.text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                ClipBoardUtils.putTextIntoClipboard(requireContext(), text, false)
                Common.showToast(getString(R.string.debug_bulk_dl_copied))
            }
        }

        runDiag()
    }

    private fun refreshFingerprint() {
        val cid = EventReporter.currentClientId()
        fingerprintText.text = cid.ifEmpty { getString(R.string.event_history_client_id_not_ready) }
    }

    private fun runDiag() {
        outputText.text = getString(R.string.debug_bulk_dl_running)
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // 全部 IO 在后台跑(目录递归 + 多次 SELECT COUNT/SUM),12k+ 行表
            // 主线程跑会 ANR
            val report = withContext(Dispatchers.IO) { buildReport(ctx) }
            outputText.text = report
            outputScroll.post { outputScroll.smoothScrollTo(0, 0) }
        }
    }

    private fun buildReport(ctx: android.content.Context): String {
        val sb = StringBuilder()
        sb.appendLine("生成时间: ${tsFormat.format(Date())}")
        sb.appendLine()

        // ─── 1. 私有存储分类(对照 Settings 的「应用大小 / 用户数据 / 缓存」) ───
        sb.appendLine("=== 私有存储分类 ===")
        sb.appendLine("(Settings 「缓存」 = cacheDir + externalCacheDir)")
        sb.appendLine("(Settings 「用户数据」 = filesDir + databases + shared_prefs + externalFilesDir)")
        sb.appendLine()
        val internalRoot = ctx.dataDir
        val dbDir = File(internalRoot, "databases")
        val filesDir = ctx.filesDir
        val mmkvDir = File(filesDir, "mmkv")
        val prefsDir = File(internalRoot, "shared_prefs")
        val internalCache = ctx.cacheDir
        val externalCache = ctx.externalCacheDir
        val externalFiles = ctx.getExternalFilesDir(null)

        sb.appendLine("内部 (/data/data/<pkg>/):")
        sb.appendLine("  databases/        ${humanSize(dirSize(dbDir))}  [用户数据]")
        sb.appendLine("  files/            ${humanSize(dirSize(filesDir))}  [用户数据]")
        sb.appendLine("  └─ files/mmkv/    ${humanSize(dirSize(mmkvDir))}  [用户数据]")
        sb.appendLine("  shared_prefs/     ${humanSize(dirSize(prefsDir))}  [用户数据]")
        sb.appendLine("  cache/            ${humanSize(dirSize(internalCache))}  [缓存]")
        sb.appendLine("  └─ cache/staging_dl/  ${humanSize(dirSize(File(internalCache, "staging_dl")))}  [缓存]")
        sb.appendLine()
        sb.appendLine("外部 (/sdcard/Android/data/<pkg>/):")
        sb.appendLine("  cache/            ${humanSize(dirSize(externalCache))}  [缓存]")
        sb.appendLine("  files/            ${humanSize(dirSize(externalFiles))}  [用户数据]")
        sb.appendLine()

        // ─── 2. DB 文件 ───
        sb.appendLine("=== databases/ 详细 ===")
        val dbFiles = dbDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (dbFiles.isEmpty()) {
            sb.appendLine("(空)")
        } else {
            dbFiles.forEach { f -> sb.appendLine("  ${f.name}: ${humanSize(f.length())}") }
        }
        sb.appendLine()

        // ─── 3. Room 表行数 + illustGson 总字节 ───
        sb.appendLine("=== Room 表行数 + 大字段总字节 ===")
        try {
            val db = AppDatabase.getAppDatabase(ctx).openHelper.readableDatabase
            // 行数:能确认表存在就查,不存在就 catch 后跳过(不同版本表名可能改)
            listOf(
                "illust_download_table" to "illustGson",   // 每个下载页一行,含完整 Illust JSON
                "download_queue" to "illustGson",          // 每个 enqueue illust 一行,含完整 Illust JSON
                "illust_downloading_table" to "taskGson",  // 进行中任务,含 DownloadItem JSON
                "illust_table" to null,                    // 浏览历史
                "feature_table" to null,
                "user_table" to null,
            ).forEach { (table, blobCol) ->
                val count = try {
                    db.query("SELECT COUNT(*) FROM $table").use { c ->
                        if (c.moveToFirst()) c.getLong(0) else -1L
                    }
                } catch (e: Exception) {
                    sb.appendLine("  $table: 查询失败 (${e.javaClass.simpleName})")
                    return@forEach
                }
                if (blobCol != null) {
                    val sum = try {
                        db.query("SELECT SUM(LENGTH($blobCol)) FROM $table").use { c ->
                            if (c.moveToFirst()) c.getLong(0) else 0L
                        }
                    } catch (_: Exception) { 0L }
                    sb.appendLine("  $table: $count 行, $blobCol 总字节 ${humanSize(sum)}")
                } else {
                    sb.appendLine("  $table: $count 行")
                }
            }
            // download_queue 按 status 拆分一下 —— SUCCESS 行如果不手动清空会无限堆,
            // 这是设计层面的已知坑(只有 DoneListV3Fragment.kt:128 的清空按钮会触发
            // deleteByStatus(SUCCESS))
            sb.appendLine()
            sb.appendLine("download_queue 按 status:")
            try {
                db.query("SELECT status, COUNT(*) FROM download_queue GROUP BY status").use { c ->
                    var any = false
                    while (c.moveToNext()) {
                        sb.appendLine("  ${c.getString(0)}: ${c.getLong(1)}")
                        any = true
                    }
                    if (!any) sb.appendLine("  (空)")
                }
            } catch (e: Exception) {
                sb.appendLine("  查询失败 (${e.javaClass.simpleName})")
            }
        } catch (e: Exception) {
            sb.appendLine("  访问 Room DB 失败: ${e.message}")
        }
        sb.appendLine()

        // ─── 4. staging_dl/ 残留 ───
        // staging 设计上 commit 后立刻 delete (Manager.java:812)。如果这里看到大量
        // 文件,说明 commit 路径有泄漏 → 应该把它们清掉。如果只有 0-3 个,基本是
        // 当前在飞的下载,正常。
        sb.appendLine("=== cache/staging_dl/ 残留 ===")
        val stage = File(internalCache, "staging_dl")
        if (!stage.exists()) {
            sb.appendLine("(目录不存在)")
        } else {
            val files = stage.listFiles() ?: emptyArray()
            sb.appendLine("文件数: ${files.size}, 总大小: ${humanSize(files.sumOf { it.length() })}")
            if (files.isNotEmpty()) {
                sb.appendLine("最大 10 条:")
                files.sortedByDescending { it.length() }.take(10).forEach { f ->
                    sb.appendLine("  ${f.name}: ${humanSize(f.length())}, mtime=${tsFormat.format(Date(f.lastModified()))}")
                }
            }
        }
        sb.appendLine()

        // ─── 5. MMKV 文件 ───
        // api-cache 这种 ResponseStore (ResponseStore.kt:26) 把整页 API JSON 缓在
        // 这里,无 evict。如果 mmkv/ 几百 MB 就是它。
        sb.appendLine("=== files/mmkv/ 详细 ===")
        if (!mmkvDir.exists()) {
            sb.appendLine("(目录不存在)")
        } else {
            val mmkvFiles = mmkvDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".crc") }
                ?.sortedByDescending { it.length() }
                ?: emptyList()
            if (mmkvFiles.isEmpty()) {
                sb.appendLine("(空)")
            } else {
                mmkvFiles.forEach { f -> sb.appendLine("  ${f.name}: ${humanSize(f.length())}") }
            }
        }
        sb.appendLine()

        // ─── 6. 结论指引(给非技术用户看的) ───
        sb.appendLine("=== 怎么看 ===")
        sb.appendLine("• 「3 GB 用户数据」如果来自批量下载,理论上只能落在以下两类:")
        sb.appendLine("  - databases/ 内的 illust_download_table / download_queue 行 (illustGson)")
        sb.appendLine("  - files/mmkv/api-cache (API response 缓存)")
        sb.appendLine("• 批量下载的 staging 文件全部在 cache/staging_dl/,算「缓存」不是「用户数据」,")
        sb.appendLine("  所以如果上面 cache/ 跟 Settings 的「缓存」数字差不多,就排除 staging 泄漏。")

        return sb.toString()
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        if (dir.isFile) return dir.length()
        var sum = 0L
        // walkTopDown 在符号链接 / 循环上有 stack overflow 风险,private storage
        // 不会有这种情况,但仍 try-wrap 兜底。
        try {
            dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        } catch (_: Throwable) {
        }
        return sum
    }

    private fun humanSize(bytes: Long): String {
        val abs = if (bytes < 0) 0 else bytes
        if (abs < 1024) return "$abs B"
        if (abs < 1024L * 1024) return "%.2f KB".format(abs / 1024.0)
        if (abs < 1024L * 1024 * 1024) return "%.2f MB".format(abs / (1024.0 * 1024))
        return "%.2f GB".format(abs / (1024.0 * 1024 * 1024))
    }
}
