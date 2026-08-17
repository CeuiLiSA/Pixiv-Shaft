package ceui.pixiv.ui.download

import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.models.IllustsBean
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.RelativePath
import com.hjq.toast.Toaster
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 共享的"导出图片 original 直链"流程。三处会触发：
 *  - 下载管理 tab 0 批量队列 ([QueueListV3Fragment]) — 队列 active row 的 illust
 *  - 下载管理 tab 2 已完成   ([DoneListV3Fragment])  — 已完成下载的 illust
 *  - 批量选择页 ([ceui.pixiv.ui.bulk.BulkSelectV3Fragment]) — 当前已勾选的 illust
 *
 * 输出：每行一个 `https://i.pximg.net/img-original/...` 直链，多 P 作品占
 * 多行（每张 page 一行），方便用户事后拿这份 .txt 用第三方下载器/IDM 重抓
 * 缺漏页，或对账多 p 下载完整性（用户的核心使用场景）。
 *
 * 流程：
 *  1. 调用方在 IO 线程把 [IllustsBean] 列表喂给 [originalUrlsOf] 抽出 url 列表
 *  2. 主线程 [present] 弹二选一菜单：保存为 .txt / 通过系统分享面板发出
 *  3. 落盘走 [ceui.pixiv.download.Downloads.openRaw]
 *     ([Bucket.Log], `ShaftFiles/pixiv-links-yyyyMMdd-HHmmss.txt`)。
 *  4. 落盘成功后**尝试**用第三方 app 打开 .txt，见 [tryOpenSavedFile]。
 *
 * 调用方负责把"从 IllustsBean 抽 url"放在 IO 线程，[present] 主线程接收
 * 现成的 [List]<String>。
 */
internal object DownloadExportLinks {

    fun present(host: Fragment, urls: List<String>) {
        if (host.context == null) return
        if (urls.isEmpty()) {
            Toaster.showShort(R.string.dlmgr_done_export_empty)
            return
        }
        val text = urls.joinToString("\n")
        showChoiceDialog(host, text, urls.size)
    }

    private fun showChoiceDialog(host: Fragment, text: String, illustCount: Int) {
        val act = host.activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        val items = arrayOf(
            host.getString(R.string.dlmgr_done_export_choose_save),
            host.getString(R.string.dlmgr_done_export_choose_share),
        )
        WitDialog.MenuDialogBuilder(act)
            .setTitle(host.getString(R.string.dlmgr_done_export_summary, illustCount))
            .addItems(items) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> saveToFile(host, text)
                    1 -> share(host, text)
                }
            }
            .show()
    }

    /**
     * 走系统分享面板把链接列表发出去。`createChooser` 已经能兜住"无 app 处理"
     * 的情况进系统对话框；外层 [runCatching] 再兜一层极端 ROM 上 chooser
     * stub 自身缺失或抛 SecurityException 的情况，避免崩进程。跟
     * [tryOpenSavedFile] 同等防护。
     */
    private fun share(host: Fragment, text: String) {
        val title = host.getString(R.string.dlmgr_done_export_share_title)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            host.startActivity(Intent.createChooser(send, title))
        }.onFailure { e ->
            // 这里 share() 是 dialog click 同步路径，不在协程内 — CancellationException
            // 不会出现，但 rethrow guard 仍加上保持跟 tryOpenSavedFile 风格一致，
            // 也防未来重构成 suspend 时被坑。
            if (e is CancellationException) throw e
            Timber.tag("ExportLinks").w(e, "share intent failed")
        }
    }

    /**
     * 落盘成 `ShaftFiles/pixiv-links-yyyyMMdd-HHmmss.txt`。用
     * [OutputStreamWriter] 直接编码，省一次 `toByteArray` 中间数组。
     * Locale.US 强制 ASCII 数字 — 默认 locale 在 ar/fa/my 下会输出本地数字，
     * 文件名跨工具兼容性差。
     */
    private fun saveToFile(host: Fragment, text: String) {
        val ctx = host.requireContext().applicationContext
        host.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val rawPath = RelativePath.parse("ShaftFiles/pixiv-links-$ts.txt")
                    val handle = DownloadsRegistry.downloads.openRaw(
                        Bucket.Log, rawPath, "text/plain",
                    ) ?: error("openRaw returned null")
                    try {
                        handle.stream.use { os ->
                            OutputStreamWriter(os, Charsets.UTF_8).use { it.write(text) }
                        }
                        handle.onFinish()
                    } catch (e: Exception) {
                        runCatching { handle.onAbort() }
                        throw e
                    }
                    SaveResult(handle.uri, rawPath.joinTo())
                }
            }
            result.fold(
                onSuccess = { saved ->
                    Toaster.showLong(host.getString(R.string.dlmgr_done_export_saved, saved.path))
                    tryOpenSavedFile(host, saved.uri)
                },
                onFailure = { e ->
                    Toaster.showLong(
                        host.getString(
                            R.string.dlmgr_done_export_save_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
                },
            )
        }
    }

    private data class SaveResult(val uri: Uri, val path: String)

    /**
     * 落盘后**尝试**用第三方 app 打开 .txt — 锦上添花，失败 silent，用户已经
     * 看到"已保存"toast，能在文件管理器找到落盘文件。
     *
     * 防崩三件套：
     *   1. **scheme guard** — 只在 `content://` 时尝试。pre-Q 的
     *      [MediaStoreBackend.openLegacy] 返回 `file://` uri，N+ 直接拿来
     *      [Intent.ACTION_VIEW] 会抛 [android.os.FileUriExposedException]
     *      崩进程；本项目 minSdk=24，无解，只能放弃。
     *   2. **createChooser 兜底** — 单 [Fragment.startActivity] 在没有任何
     *      app 声明处理 `text/plain` 时直接抛 [ActivityNotFoundException]，
     *      [Intent.createChooser] 保证回到系统的"无应用可处理"对话框而非崩。
     *   3. **try/catch 兜底兜底** — 个别精简 ROM 上 chooser stub 自身缺失，
     *      或 SecurityException 等罕见路径，仍会抛。整段 [runCatching]
     *      吞掉，只 warn log 一下供排查。
     *
     * [Intent.FLAG_GRANT_READ_URI_PERMISSION] — `content://` 跨 app 的标准
     * 授权位，目标 app 没有 READ_MEDIA_* 权限也能读这一条记录。
     */
    private fun tryOpenSavedFile(host: Fragment, uri: Uri) {
        if (uri.scheme != "content") return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            host.startActivity(Intent.createChooser(view, null))
        }.onFailure { e ->
            // Kotlin stdlib runCatching 会捕获 CancellationException — 协程内必须
            // rethrow 否则 cancel 信号被吞。本场景 startActivity 是同步无挂起
            // 点，实际不会触发，加上是为了符合协程惯例 + 防未来 refactor。
            if (e is CancellationException) throw e
            Timber.tag("ExportLinks").w(e, "open saved txt failed (uri=%s)", uri)
        }
    }
}

/**
 * 单个 illust → 它的 N 个 original 直链。
 *
 * pixiv API 的两套互斥结构：
 *  - 多 P：[IllustsBean.meta_pages] 非空，每项的 `image_urls.original` 是直链
 *  - 单 P：[IllustsBean.meta_single_page].original_image_url 是直链
 *
 * 历史数据 / 老 API 偶尔出现两边都空但 [IllustsBean.image_urls].original 有
 * 值的情况，作为 fallback 不丢导出。空字符串过滤掉。
 *
 * 调用方应该在 IO 线程内调用——遍历几千个 IllustsBean 主线程会卡帧。
 */
internal fun originalUrlsOf(illust: IllustsBean): List<String> {
    val pages = illust.meta_pages
    if (!pages.isNullOrEmpty()) {
        return pages.mapNotNull { it.image_urls?.original?.takeIf(String::isNotEmpty) }
    }
    val single = illust.meta_single_page?.original_image_url
    if (!single.isNullOrEmpty()) return listOf(single)
    val fallback = illust.image_urls?.original
    return if (!fallback.isNullOrEmpty()) listOf(fallback) else emptyList()
}
