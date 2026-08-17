package ceui.pixiv.ui.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.download.importer.DownloadImporter
import ceui.pixiv.download.importer.ImportMetadataEnricher
import ceui.pixiv.download.importer.PageBase
import ceui.pixiv.ui.bulk.FetchProgressDialog
import ceui.pixiv.witstudio.dialog.WitSkinManager
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.dialog.WitDialogView
import ceui.pixiv.witstudio.dialog.WitTipDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 「导入本地下载」的交互流程 —— issue #953。
 *
 * 用户旧版下载的图片新版认不出来（新旧文件名消毒规则不同，见 [DownloadImporter] 的注释），
 * 这里让用户选一次目录，把盘上认得出作品 id 的图片补成下载记录。
 *
 * 四步，**扫描阶段绝不写库**：
 *   1. 说明 —— 讲清楚只读所选目录、不改不删任何文件；
 *   2. SAF 选目录；
 *   3. 扫描（可取消）→ 预览：扫到多少 / 认出多少 / 要跳过多少 / 认不出的长什么样；
 *   4. 用户确认后才写库，然后可选地去补全作品信息。
 *
 * launcher 必须由宿主 fragment 注册（`registerForActivityResult` 有时机要求），
 * 所以这里只负责造 intent 和处理选中结果。
 */
class ImportLocalDownloadsFlow(private val host: Fragment) {

    private var scanJob: Job? = null

    /** 第一步：先把"要干什么、不会干什么"讲清楚，再弹系统选择器。 */
    fun start(onPick: (Intent) -> Unit) {
        val ctx = host.context ?: return
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.dlmgr_import_title)
            .setMessage(R.string.dlmgr_import_intro)
            .setSkinManager(WitSkinManager.defaultInstance(ctx))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(R.string.dlmgr_import_pick_folder) { d, _ ->
                d.dismiss()
                onPick(pickerIntent(ctx))
            }
            .create()
            .show()
    }

    /**
     * ACTION_OPEN_DOCUMENT_TREE。显式钉死 component 兜 vivo/iQOO 上隐式 intent 被
     * 重定向到 LAUNCHER 的坑 —— 同 [ceui.lisa.activities.BaseActivity.launchSafTreePicker]
     * 和本地小说书架的选目录。
     */
    private fun pickerIntent(ctx: Context): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            runCatching { resolveActivity(ctx.packageManager) }.getOrNull()?.let { component = it }
        }

    /** 用户选完目录后由宿主回调进来。 */
    fun onTreePicked(treeUri: Uri) {
        val ctx = host.context ?: return
        // 持久化读权限 —— 导入进去的 filePath 是这棵树里的 content:// uri，卡片缩略图
        // 和后续详情页复用本地文件都要靠它。丢了权限只是退化成读网图，不会崩。
        try {
            ctx.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Common.showToast(host.getString(R.string.dlmgr_import_pick_failed, e.message ?: ""))
            return
        }
        scan(treeUri)
    }

    // ---- 扫描 ---------------------------------------------------------------

    private fun scan(treeUri: Uri) {
        val ctx = host.context ?: return
        val status = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // 手工 new 的 view 不在 QMUI skin 管辖内，默认文字色是浅色主题的深灰，
            // 夜间压在深色 dialog 底上看不见 —— 跟 dialog 标题一样用日/夜自适应色。
            setTextColor(ContextCompat.getColor(ctx, R.color.rank_text_color))
            text = host.getString(R.string.dlmgr_import_scanning_start)
        }
        val progressDialog = object : WitDialog.CustomDialogBuilder(ctx) {
            override fun onCreateContent(dialog: WitDialog, parent: WitDialogView, c: Context): View =
                LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(c, 24), dp(c, 8), dp(c, 24), dp(c, 8))
                    addView(status)
                }
        }
            .setTitle(host.getString(R.string.dlmgr_import_scanning_title))
            .setSkinManager(WitSkinManager.defaultInstance(ctx))
            .addAction(host.getString(R.string.cancel)) { d, _ ->
                scanJob?.cancel()
                d.dismiss()
            }
            .create()
        // 扫描期间不许点外面关掉 —— 关了 dialog 但 job 还在跑会很迷惑。
        progressDialog.setCanceledOnTouchOutside(false)
        progressDialog.show()

        scanJob = host.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val plan = DownloadImporter.scanAndPlan(
                    context = ctx.applicationContext,
                    treeUri = treeUri,
                ) { scanned, recognized ->
                    // 扫描在 IO 上跑，回调也在 IO。先用稳定的 Context 算好文本，再向
                    // status 自己 post；Runnable 执行时绝不能再碰 host Fragment ——
                    // 用户刚退出页面时 Fragment 可能已经 detach，host.getString() 会在
                    // 主线程抛 IllegalStateException。
                    val text = ctx.getString(
                        R.string.dlmgr_import_scanning_status, scanned, recognized,
                    )
                    status.post {
                        status.text = text
                    }
                }
                if (progressDialog.isShowing) runCatching { progressDialog.dismiss() }
                showPreview(plan)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "扫描失败")
                Common.showToast(ctx.getString(R.string.dlmgr_import_failed, e.message ?: ""))
            } finally {
                // viewLifecycle 结束也会取消扫描；无论正常、失败还是取消，都收掉 Activity
                // window，避免离开下载管理页后 loading dialog 继续悬着。
                if (progressDialog.isShowing) runCatching { progressDialog.dismiss() }
                // 用户取消后可能很快又发起一次扫描；旧任务收尾时不能把新任务句柄清掉。
                if (scanJob === coroutineContext[Job]) scanJob = null
            }
        }
    }

    // ---- 预览 + 确认 ---------------------------------------------------------

    private fun showPreview(plan: DownloadImporter.ImportPlan) {
        val ctx = host.context ?: return
        if (plan.newRows == 0) {
            WitDialog.MessageDialogBuilder(ctx)
                .setTitle(R.string.dlmgr_import_title)
                .setMessage(emptyReason(plan))
                .setSkinManager(WitSkinManager.defaultInstance(ctx))
                .addAction(R.string.sure) { d, _ -> d.dismiss() }
                .create()
                .show()
            return
        }
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.dlmgr_import_preview_title)
            .setMessage(previewText(plan))
            .setSkinManager(WitSkinManager.defaultInstance(ctx))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(R.string.dlmgr_import_confirm) { d, _ ->
                d.dismiss()
                confirmPageBaseOrCommit(plan)
            }
            .create()
            .show()
    }

    /**
     * 只有 `p1/p2/...`、没有 `p0` 的旧文件，单看文件名无法判断它来自 0 基还是 1 基配置。
     * 以前直接把这些行写成 page=-2，结果只能点亮作品级“已下载”徽标，“已存在则跳过”
     * 和详情页本地复用仍然失效。旧版页码基准是全局设置，所以这里让用户一次选择后，
     * 在内存中的 [DownloadImporter.ImportPlan] 上补齐全部模棱两可的页码，再进入写库。
     */
    private fun confirmPageBaseOrCommit(plan: DownloadImporter.ImportPlan) {
        if (plan.ambiguousPageRows == 0) {
            commit(plan)
            return
        }
        val ctx = host.context ?: return
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.dlmgr_import_page_base_title)
            .setMessage(
                host.getString(
                    R.string.dlmgr_import_page_base_message,
                    plan.ambiguousPageRows,
                ),
            )
            .setSkinManager(WitSkinManager.defaultInstance(ctx))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(R.string.dlmgr_import_page_base_zero) { d, _ ->
                d.dismiss()
                commit(plan.resolveAmbiguousPages(PageBase.ZERO))
            }
            .addAction(
                0,
                R.string.dlmgr_import_page_base_one,
                WitDialogAction.ACTION_PROP_POSITIVE,
            ) { d, _ ->
                d.dismiss()
                commit(plan.resolveAmbiguousPages(PageBase.ONE))
            }
            .create()
            .show()
    }

    private fun previewText(plan: DownloadImporter.ImportPlan): CharSequence = buildString {
        appendLine(host.getString(R.string.dlmgr_import_stat_scanned, plan.scannedFiles))
        appendLine(host.getString(R.string.dlmgr_import_stat_recognized, plan.works, plan.recognizedFiles))
        if (plan.alreadyRecorded > 0) {
            appendLine(host.getString(R.string.dlmgr_import_stat_existing, plan.alreadyRecorded))
        }
        appendLine(host.getString(R.string.dlmgr_import_stat_new, plan.newWorks, plan.newRows))
        if (plan.unrecognized > 0) {
            appendLine()
            appendLine(host.getString(R.string.dlmgr_import_stat_unrecognized, plan.unrecognized))
            plan.unrecognizedSamples.forEach { appendLine("· $it") }
        }
    }.trimEnd()

    /** 一条都没得写时，把原因说清楚 —— 用户最怕的是点完没反应。 */
    private fun emptyReason(plan: DownloadImporter.ImportPlan): CharSequence = when {
        plan.scannedFiles == 0 -> host.getString(R.string.dlmgr_import_empty_no_files)
        plan.recognizedFiles == 0 -> host.getString(R.string.dlmgr_import_empty_none_recognized)
        else -> host.getString(R.string.dlmgr_import_empty_all_existing, plan.alreadyRecorded)
    }

    // ---- 写库 ---------------------------------------------------------------

    private fun commit(plan: DownloadImporter.ImportPlan) {
        val ctx = host.context ?: return
        val writing = WitTipDialog.Builder(ctx)
            .setTipWord(host.getString(R.string.dlmgr_import_writing))
            .create()
        writing.show()

        host.viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                // commit 内部已经 withContext(Dispatchers.IO)
                DownloadImporter.commit(ctx.applicationContext, plan)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "导入写库失败")
                Common.showToast(ctx.getString(R.string.dlmgr_import_failed, e.message ?: ""))
                null
            } finally {
                // 放 finally：视图销毁导致协程取消时也收得掉，loading 不会挂在 activity 上泄漏窗口
                if (writing.isShowing) runCatching { writing.dismiss() }
            }
            if (result != null) showDone(result)
        }
    }

    private fun showDone(result: DownloadImporter.ImportResult) {
        val ctx = host.context ?: return
        val pending = ImportMetadataEnricher.pendingCount()
        val builder = WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.dlmgr_import_done_title)
            .setMessage(
                host.getString(R.string.dlmgr_import_done_message, result.works, result.inserted),
            )
            .setSkinManager(WitSkinManager.defaultInstance(ctx))
        if (pending > 0) {
            builder.addAction(R.string.dlmgr_import_enrich_later) { d, _ -> d.dismiss() }
            builder.addAction(0, R.string.dlmgr_import_enrich_now, WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                d.dismiss()
                confirmEnrich(pending)
            }
        } else {
            builder.addAction(R.string.sure) { d, _ -> d.dismiss() }
        }
        builder.create().show()
    }

    // ---- 补全元数据 ---------------------------------------------------------

    /**
     * 补全是 2s/个的限速任务，几千个作品要跑很久。先把耗时讲清楚再开跑，
     * 别让用户以为卡死了。
     */
    private fun confirmEnrich(pending: Int) {
        val ctx = host.context ?: return
        val minutes = ((pending * 2L) / 60L).coerceAtLeast(1L)
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.dlmgr_import_enrich_now)
            .setMessage(host.getString(R.string.dlmgr_import_enrich_message, pending, minutes))
            .setSkinManager(WitSkinManager.defaultInstance(ctx))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(R.string.sure) { d, _ ->
                d.dismiss()
                startEnrich()
            }
            .create()
            .show()
    }

    /** 进度直接复用 [FetchProgressDialog] —— 它本来就是给"限速逐条拉 API"用的。 */
    fun startEnrich() {
        val ctx = host.context ?: return
        FetchProgressDialog.show(
            host.childFragmentManager,
            ImportMetadataEnricher.enrich(ctx.applicationContext),
            FetchProgressDialog.Config(
                title = "import-enrich",
                headerCmd = "\$ shaft import --enrich-metadata",
                showOpenManager = false,
                itemNoun = "works",
                stepNoun = "work",
                completedVerb = "updated",
                canceledVerb = "updated",
            ),
        )
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ImportLocalDownloads"
    }
}
