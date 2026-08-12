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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.utils.Common
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import java.io.File

class UpdateBottomSheet : BottomSheetDialogFragment() {

    private var release: GitHubRelease? = null
    private var downloadId: Long = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var downloadReceiver: BroadcastReceiver? = null
    private var pendingInstallFile: File? = null

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
    ): View {
        return inflater.inflate(R.layout.dialog_app_update, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rel = release ?: run { dismiss(); return }

        val versionText = view.findViewById<TextView>(R.id.update_version_info)
        val changelogText = view.findViewById<TextView>(R.id.update_changelog)
        val progressContainer = view.findViewById<LinearLayout>(R.id.progress_container)
        val progressBar = view.findViewById<ProgressBar>(R.id.download_progress)
        val progressText = view.findViewById<TextView>(R.id.progress_text)
        val btnDownload = view.findViewById<Button>(R.id.btn_download)
        val btnLater = view.findViewById<Button>(R.id.btn_later)
        val btnSkip = view.findViewById<Button>(R.id.btn_skip_version)

        val remoteVersion = rel.tagName.removePrefix("v").removePrefix("V")
        versionText.text = getString(R.string.update_version_format, BuildConfig.VERSION_NAME, remoteVersion)

        val textColor = ContextCompat.getColor(requireContext(), R.color.rank_text_color)
        val markwon = Markwon.builder(requireContext())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.headingTextSizeMultipliers(floatArrayOf(1.3f, 1.15f, 1.05f, 1f, 0.9f, 0.85f))
                    builder.linkColor(ContextCompat.getColor(requireContext(), R.color.user_name_horizontal))
                }
            })
            .build()
        val body = rel.body
        if (!body.isNullOrBlank()) {
            markwon.setMarkdown(changelogText, body)
        } else {
            changelogText.setText(R.string.update_no_changelog)
        }

        btnLater.setOnClickListener { dismiss() }

        btnSkip.setOnClickListener {
            AppUpdateChecker.skipVersion(remoteVersion)
            Common.showToast(getString(R.string.update_version_skipped))
            dismiss()
        }

        btnDownload.setOnClickListener {
            val asset = AppUpdateChecker.findApkAsset(rel)
            if (asset == null) {
                openInBrowser(rel.htmlUrl ?: "https://github.com/${GitHubApi.OWNER}/${GitHubApi.REPO}/releases/latest")
                return@setOnClickListener
            }

            btnDownload.isEnabled = false
            btnDownload.setText(R.string.update_downloading)
            btnSkip.visibility = View.GONE
            progressContainer.visibility = View.VISIBLE

            startDownload(asset, progressBar, progressText, btnDownload, progressContainer)
        }

        restoreOngoingDownload(rel.tagName, progressBar, progressText, btnDownload, btnSkip, progressContainer)

        // Expand the bottom sheet fully
        (dialog as? BottomSheetDialog)?.apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            // Apply navigation bar padding for safe area
            window?.let { window ->
                ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                    val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBar.bottom)
                    insets
                }
            }
        }
    }

    private fun startDownload(
        asset: GitHubAsset,
        progressBar: ProgressBar,
        progressText: TextView,
        btnDownload: Button,
        progressContainer: LinearLayout
    ) {
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
                    attachToDownload(existingId, apkFile, progressBar, progressText, btnDownload, progressContainer)
                    return
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (apkFile.exists()) {
                        onDownloadSuccess(apkFile, progressBar, progressText, btnDownload)
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
        attachToDownload(newId, apkFile, progressBar, progressText, btnDownload, progressContainer)
    }

    private fun attachToDownload(
        id: Long,
        apkFile: File,
        progressBar: ProgressBar,
        progressText: TextView,
        btnDownload: Button,
        progressContainer: LinearLayout
    ) {
        downloadId = id
        val ctx = requireContext().applicationContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val received = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (received == downloadId) {
                    onDownloadSuccess(apkFile, progressBar, progressText, btnDownload)
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

        startProgressPolling(dm, progressBar, progressText, btnDownload, progressContainer, apkFile)
    }

    private fun onDownloadSuccess(
        apkFile: File,
        progressBar: ProgressBar,
        progressText: TextView,
        btnDownload: Button
    ) {
        stopProgressPolling()
        AppUpdateChecker.clearOngoingDownload()
        // DM 在某些 OEM 上会把"网络中断时截断的文件"也标成 STATUS_SUCCESSFUL,
        // 装出来就是"解析包出错"。拿 GitHub asset.size 兜个底
        if (!isApkComplete(apkFile)) {
            if (apkFile.exists()) apkFile.delete()
            Common.showToast(getString(R.string.update_apk_invalid))
            progressBar.progress = 0
            progressText.setText(R.string.update_download_failed)
            btnDownload.isEnabled = true
            btnDownload.setText(R.string.update_retry)
            return
        }
        progressBar.progress = 100
        progressText.text = "100%"
        btnDownload.isEnabled = true
        btnDownload.setText(R.string.update_install)
        btnDownload.setOnClickListener { installApk(apkFile) }
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

    private fun restoreOngoingDownload(
        versionTag: String,
        progressBar: ProgressBar,
        progressText: TextView,
        btnDownload: Button,
        btnSkip: Button,
        progressContainer: LinearLayout
    ) {
        val ctx = requireContext().applicationContext
        val downloadDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val apkFile = File(downloadDir, APK_FILE_NAME)
        val existingId = AppUpdateChecker.getOngoingDownloadId(versionTag)
        if (existingId == -1L) return

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        when (queryDownloadStatus(dm, existingId)) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> {
                btnDownload.isEnabled = false
                btnDownload.setText(R.string.update_downloading)
                btnSkip.visibility = View.GONE
                progressContainer.visibility = View.VISIBLE
                attachToDownload(existingId, apkFile, progressBar, progressText, btnDownload, progressContainer)
            }
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (isApkComplete(apkFile)) {
                    btnSkip.visibility = View.GONE
                    progressContainer.visibility = View.VISIBLE
                    progressBar.progress = 100
                    progressText.text = "100%"
                    btnDownload.setText(R.string.update_install)
                    btnDownload.setOnClickListener { installApk(apkFile) }
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

    private fun startProgressPolling(
        dm: DownloadManager,
        progressBar: ProgressBar,
        progressText: TextView,
        btnDownload: Button,
        progressContainer: LinearLayout,
        apkFile: File
    ) {
        progressRunnable = object : Runnable {
            override fun run() {
                if (downloadId == -1L) return
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
                                    progressBar.progress = percent
                                    val downloadedMB = bytesDownloaded / 1048576f
                                    val totalMB = totalBytes / 1048576f
                                    progressText.text = String.format("%.1fMB / %.1fMB (%d%%)", downloadedMB, totalMB, percent)
                                }
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                onDownloadSuccess(apkFile, progressBar, progressText, btnDownload)
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                AppUpdateChecker.clearOngoingDownload()
                                progressText.setText(R.string.update_download_failed)
                                btnDownload.isEnabled = true
                                btnDownload.setText(R.string.update_retry)
                                btnDownload.setOnClickListener {
                                    val rel = release ?: return@setOnClickListener
                                    val asset = AppUpdateChecker.findApkAsset(rel) ?: return@setOnClickListener
                                    btnDownload.isEnabled = false
                                    btnDownload.setText(R.string.update_downloading)
                                    progressBar.progress = 0
                                    startDownload(asset, progressBar, progressText, btnDownload, progressContainer)
                                }
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
            val fallback = release?.htmlUrl
                ?: "https://github.com/${GitHubApi.OWNER}/${GitHubApi.REPO}/releases/latest"
            openInBrowser(fallback)
        }
    }

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
        downloadReceiver?.let {
            try {
                requireContext().applicationContext.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        downloadReceiver = null
    }
}
