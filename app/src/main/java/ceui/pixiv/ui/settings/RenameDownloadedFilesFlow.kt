package ceui.pixiv.ui.settings

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.download.maintenance.RenameSweeper
import ceui.pixiv.ui.bulk.FetchProgressDialog
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.qmuiteam.qmui.widget.dialog.QMUIDialogView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 「把已下载文件重命名成当前命名格式」的交互流程 —— issue #567。
 * 结构照抄 [ceui.pixiv.ui.download.ImportLocalDownloadsFlow]：
 * 说明 → 扫描（可取消，绝不写库）→ 预览统计 → 确认 → 执行（FetchProgressDialog 进度）。
 */
class RenameDownloadedFilesFlow(private val host: Fragment) {

    private var scanJob: Job? = null

    /** 命名模板改动落盘后追问一句 —— issue #567 要的就是这个入口。 */
    fun promptAfterTemplateChange() {
        val ctx = host.context ?: return
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.rename_dl_title)
            .setMessage(R.string.rename_dl_prompt_after_save)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(R.string.rename_dl_later) { d, _ -> d.dismiss() }
            .addAction(0, R.string.rename_dl_go, QMUIDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                d.dismiss()
                scan()
            }
            .create()
            .show()
    }

    /** 设置页常驻入口：先把「会做什么、不会做什么」讲清楚再扫。 */
    fun start() {
        val ctx = host.context ?: return
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.rename_dl_title)
            .setMessage(R.string.rename_dl_intro)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.rename_dl_scan, QMUIDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                d.dismiss()
                scan()
            }
            .create()
            .show()
    }

    // ---- 扫描 ---------------------------------------------------------------

    private fun scan() {
        val ctx = host.context ?: return
        val status = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = host.getString(R.string.rename_dl_scanning_start)
        }
        val progressDialog = object : QMUIDialog.CustomDialogBuilder(ctx) {
            override fun onCreateContent(dialog: QMUIDialog, parent: QMUIDialogView, c: Context): View =
                LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(c, 24), dp(c, 8), dp(c, 24), dp(c, 8))
                    addView(status)
                }
        }
            .setTitle(host.getString(R.string.rename_dl_scanning_title))
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(host.getString(R.string.cancel)) { d, _ ->
                scanJob?.cancel()
                d.dismiss()
            }
            .create()
        progressDialog.setCanceledOnTouchOutside(false)
        progressDialog.show()

        scanJob = host.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val plan = RenameSweeper.plan(ctx.applicationContext) { scanned, toRename ->
                    // 回调在 IO 线程。先用稳定的 Context 算好文本再向 status 自己 post；
                    // Runnable 执行时绝不能碰 host Fragment（可能已 detach）。
                    val text = ctx.getString(R.string.rename_dl_scanning_status, scanned, toRename)
                    status.post { status.text = text }
                }
                if (progressDialog.isShowing) runCatching { progressDialog.dismiss() }
                showPreview(plan)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "扫描失败")
                Common.showToast(ctx.getString(R.string.rename_dl_failed, e.message ?: ""))
            } finally {
                if (progressDialog.isShowing) runCatching { progressDialog.dismiss() }
                if (scanJob === coroutineContext[Job]) scanJob = null
            }
        }
    }

    // ---- 预览 + 确认 ---------------------------------------------------------

    private fun showPreview(plan: RenameSweeper.RenamePlan) {
        val ctx = host.context ?: return
        if (plan.entries.isEmpty()) {
            QMUIDialog.MessageDialogBuilder(ctx)
                .setTitle(R.string.rename_dl_title)
                .setMessage(previewText(plan, empty = true))
                .setSkinManager(QMUISkinManager.defaultInstance(ctx))
                .addAction(R.string.sure) { d, _ -> d.dismiss() }
                .create()
                .show()
            return
        }
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.rename_dl_preview_title)
            .setMessage(previewText(plan, empty = false))
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.rename_dl_confirm, QMUIDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                d.dismiss()
                commit(plan)
            }
            .create()
            .show()
    }

    private fun previewText(plan: RenameSweeper.RenamePlan, empty: Boolean): CharSequence = buildString {
        if (empty) appendLine(host.getString(R.string.rename_dl_empty))
        appendLine(host.getString(R.string.rename_dl_stat_total, plan.totalRows))
        if (plan.alreadyMatching > 0) {
            appendLine(host.getString(R.string.rename_dl_stat_matching, plan.alreadyMatching))
        }
        if (!empty) appendLine(host.getString(R.string.rename_dl_stat_to_rename, plan.entries.size))
        val skips = listOf(
            plan.fileMissing to R.string.rename_dl_stat_missing,
            plan.needsEnrich to R.string.rename_dl_stat_enrich,
            plan.unknownPage to R.string.rename_dl_stat_page,
            plan.nameConflicts to R.string.rename_dl_stat_conflict,
            plan.broken to R.string.rename_dl_stat_broken,
        ).filter { it.first > 0 }
        if (skips.isNotEmpty()) {
            appendLine()
            skips.forEach { (count, res) -> appendLine("· " + host.getString(res, count)) }
        }
    }.trimEnd()

    // ---- 执行 ---------------------------------------------------------------

    private fun commit(plan: RenameSweeper.RenamePlan) {
        val ctx = host.context ?: return
        FetchProgressDialog.show(
            host.childFragmentManager,
            RenameSweeper.commit(ctx.applicationContext, plan),
            FetchProgressDialog.Config(
                title = "rename-downloads",
                headerCmd = "\$ shaft rename --apply-current-template",
                showOpenManager = false,
                itemNoun = "files",
                stepNoun = "file",
                completedVerb = "renamed",
                canceledVerb = "renamed",
            ),
        )
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "RenameDownloadedFiles"
    }
}
