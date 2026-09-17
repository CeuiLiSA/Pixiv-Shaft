package ceui.lisa.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.utils.Common
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import java.util.Locale

/**
 * 「发现新版本」弹窗：长相全在 [UpdateSheetView]（V3 弹窗配方），这里只管
 * DownloadManager 的入队 / 续接 / 进度轮询 / 完整性校验 / 安装授权。
 */
class UpdateBottomSheet : BottomSheetDialogFragment() {

    private var release: GitHubRelease? = null
    private var downloadId: Long = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var downloadReceiver: BroadcastReceiver? = null
    private var pendingInstallFile: File? = null

    /** 随视图创建 / 销毁；轮询回调一律先取它，拿不到就说明视图已经没了。 */
    private var sheet: UpdateSheetView? = null

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val file = pendingInstallFile ?: return@registerForActivityResult
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            requireContext().packageManager.canRequestPackageInstalls()
        ) {
            doInstall(file)
        }
    }

    companion object {
        private const val APK_FILE_NAME = "shaft-update.apk"

        fun newInstance(release: GitHubRelease): UpdateBottomSheet {
            return UpdateBottomSheet().apply {
                this.release = release
            }
        }
    }

    override fun getTheme(): Int = R.style.Theme_Update_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = UpdateSheetView(requireContext()).also { sheet = it }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rel = release ?: run { dismiss(); return }
        val sheet = sheet ?: return

        sheet.bind(BuildConfig.VERSION_NAME, rel, markwonFor(requireContext()))
        sheet.setPrimary(R.string.update_download, enabled = true) { beginDownload(rel) }
        sheet.later.setOnClickListener { dismiss() }
        sheet.skip.setOnClickListener {
            AppUpdateChecker.skipVersion(rel.versionName)
            Common.showToast(getString(R.string.update_version_skipped))
            dismiss()
        }

        restoreOngoingDownload(rel.tagName)

        (dialog as? BottomSheetDialog)?.apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            // 安全区由内容自己垫：sheet 贴到屏幕底，动作胶囊不能压在手势条上。
            val basePadding = view.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.updatePadding(bottom = basePadding + navBar.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(view)
        }
    }

    /** 主操作按下：没有 apk 资产就退回浏览器打开 release 页。 */
    private fun beginDownload(rel: GitHubRelease) {
        val asset = AppUpdateChecker.findApkAsset(rel)
        if (asset == null) {
            openInBrowser(rel.htmlUrl ?: releasesUrl())
            return
        }
        val sheet = sheet ?: return
        sheet.setPrimary(R.string.update_downloading, enabled = false)
        sheet.showProgress()
        startDownload(asset)
    }

    private fun startDownload(asset: GitHubAsset) {
        val ctx = requireContext().applicationContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val tag = release?.tagName ?: ""
        val downloadDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val apkFile = File(downloadDir, APK_FILE_NAME)

        // If we already have a live record for this version, attach to it instead of re-enqueuing
        // (otherwise DownloadManager would discard the partial file and restart from 0)
        val existingId = AppUpdateChecker.getOngoingDownloadId(tag)
        if (existingId != -1L) {
            when (queryDownloadStatus(dm, existingId)) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    attachToDownload(existingId, apkFile)
                    return
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (apkFile.exists()) {
                        onDownloadSuccess(apkFile)
                        return
                    }
                }
            }
            try { dm.remove(existingId) } catch (_: Exception) {}
            AppUpdateChecker.clearOngoingDownload()
        }

        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
            .setTitle(getString(R.string.update_download_title))
            .setDescription("Shaft $tag")
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val newId = dm.enqueue(request)
        AppUpdateChecker.saveOngoingDownload(newId, tag)
        attachToDownload(newId, apkFile)
    }

    private fun attachToDownload(id: Long, apkFile: File) {
        downloadId = id
        val ctx = requireContext().applicationContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val received = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (received == downloadId) {
                    onDownloadSuccess(apkFile)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            ctx.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }

        startProgressPolling(dm, apkFile)
    }

    private fun onDownloadSuccess(apkFile: File) {
        stopProgressPolling()
        AppUpdateChecker.clearOngoingDownload()
        val sheet = sheet ?: return
        // DM 在某些 OEM 上会把"网络中断时截断的文件"也标成 STATUS_SUCCESSFUL,
        // 装出来就是"解析包出错"。拿 GitHub asset.size 兜个底
        if (!isApkComplete(apkFile)) {
            if (apkFile.exists()) apkFile.delete()
            Common.showToast(getString(R.string.update_apk_invalid))
            sheet.resetProgress()
            sheet.setProgressMessage(R.string.update_download_failed)
            sheet.setPrimary(R.string.update_retry, enabled = true) { retryDownload() }
            return
        }
        sheet.setProgress(100, "100%")
        sheet.setPrimary(
            R.string.update_install,
            enabled = true,
            icon = R.drawable.ic_file_download_done_24dp,
        ) { installApk(apkFile) }
    }

    private fun retryDownload() {
        val rel = release ?: return
        val asset = AppUpdateChecker.findApkAsset(rel) ?: return
        val sheet = sheet ?: return
        sheet.setPrimary(R.string.update_downloading, enabled = false)
        sheet.resetProgress()
        startDownload(asset)
    }

    private fun isApkComplete(apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0) return false
        val expected = release?.let { AppUpdateChecker.findApkAsset(it)?.size } ?: return true
        if (expected <= 0) return true
        return apkFile.length() == expected
    }

    private fun queryDownloadStatus(dm: DownloadManager, id: Long): Int {
        var cursor: Cursor? = null
        try {
            cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor != null && cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIdx >= 0) return cursor.getInt(statusIdx)
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return -1
    }

    private fun restoreOngoingDownload(versionTag: String) {
        val ctx = requireContext().applicationContext
        val downloadDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val apkFile = File(downloadDir, APK_FILE_NAME)
        val existingId = AppUpdateChecker.getOngoingDownloadId(versionTag)
        if (existingId == -1L) return
        val sheet = sheet ?: return

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        when (queryDownloadStatus(dm, existingId)) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> {
                sheet.setPrimary(R.string.update_downloading, enabled = false)
                sheet.showProgress()
                attachToDownload(existingId, apkFile)
            }
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (isApkComplete(apkFile)) {
                    sheet.showProgress()
                    sheet.setProgress(100, "100%")
                    sheet.setPrimary(
            R.string.update_install,
            enabled = true,
            icon = R.drawable.ic_file_download_done_24dp,
        ) { installApk(apkFile) }
                } else {
                    if (apkFile.exists()) apkFile.delete()
                    AppUpdateChecker.clearOngoingDownload()
                    Common.showToast(getString(R.string.update_apk_invalid))
                }
            }
            else -> {
                try { dm.remove(existingId) } catch (_: Exception) {}
                if (apkFile.exists()) apkFile.delete()
                AppUpdateChecker.clearOngoingDownload()
                Common.showToast(getString(R.string.update_download_failed))
            }
        }
    }

    private fun startProgressPolling(dm: DownloadManager, apkFile: File) {
        progressRunnable = object : Runnable {
            override fun run() {
                if (downloadId == -1L) return
                val sheet = sheet ?: return
                val query = DownloadManager.Query().setFilterById(downloadId)
                var cursor: Cursor? = null
                try {
                    cursor = dm.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        val status = cursor.getInt(statusIdx)
                        val bytesDownloaded = cursor.getLong(bytesIdx)
                        val totalBytes = cursor.getLong(totalIdx)

                        when (status) {
                            DownloadManager.STATUS_RUNNING -> {
                                if (totalBytes > 0) {
                                    val percent = (bytesDownloaded * 100 / totalBytes).toInt()
                                    sheet.setProgress(
                                        percent,
                                        String.format(
                                            Locale.getDefault(),
                                            "%.1f MB / %.1f MB (%d%%)",
                                            bytesDownloaded / 1048576f,
                                            totalBytes / 1048576f,
                                            percent,
                                        ),
                                    )
                                }
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                onDownloadSuccess(apkFile)
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                AppUpdateChecker.clearOngoingDownload()
                                sheet.setProgressMessage(R.string.update_download_failed)
                                sheet.setPrimary(R.string.update_retry, enabled = true) { retryDownload() }
                                return
                            }
                        }
                    }
                } finally {
                    cursor?.close()
                }
                handler.postDelayed(this, 300)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!requireContext().packageManager.canRequestPackageInstalls()) {
                pendingInstallFile = apkFile
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${requireContext().packageName}")
                )
                installPermissionLauncher.launch(intent)
                return
            }
        }
        doInstall(apkFile)
    }

    private fun doInstall(apkFile: File) {
        val ctx = requireContext()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Common.showToast(getString(R.string.update_install_failed))
            openInBrowser(release?.htmlUrl ?: releasesUrl())
        }
    }

    private fun releasesUrl(): String =
        "https://github.com/${GitHubApi.OWNER}/${GitHubApi.REPO}/releases/latest"

    private fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Common.showToast(getString(R.string.msg_no_browser))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopProgressPolling()
        sheet = null
        downloadReceiver?.let {
            try {
                requireContext().applicationContext.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        downloadReceiver = null
    }
}
