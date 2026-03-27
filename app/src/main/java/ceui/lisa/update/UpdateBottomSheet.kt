package ceui.lisa.update

import android.app.DownloadManager
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
import android.widget.Toast
import androidx.core.content.FileProvider
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.utils.Common
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.noties.markwon.Markwon
import java.io.File

class UpdateBottomSheet : BottomSheetDialogFragment() {

    private var release: GitHubRelease? = null
    private var downloadId: Long = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var downloadReceiver: BroadcastReceiver? = null

    companion object {
        private const val APK_FILE_NAME = "shaft-update.apk"

        fun newInstance(release: GitHubRelease): UpdateBottomSheet {
            return UpdateBottomSheet().apply {
                this.release = release
            }
        }
    }

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

        val markwon = Markwon.create(requireContext())
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

        // Expand the bottom sheet fully
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
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
        val downloadDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val apkFile = File(downloadDir, APK_FILE_NAME)
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
            .setTitle(getString(R.string.update_download_title))
            .setDescription("Shaft ${release?.tagName ?: ""}")
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)

        // Register receiver for download completion
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    stopProgressPolling()
                    progressBar.progress = 100
                    progressText.text = "100%"
                    btnDownload.isEnabled = true
                    btnDownload.setText(R.string.update_install)
                    btnDownload.setOnClickListener { installApk(apkFile) }
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

        // Poll for progress
        startProgressPolling(dm, progressBar, progressText, btnDownload, progressContainer, apkFile)
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
                            DownloadManager.STATUS_FAILED -> {
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
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                )
                startActivity(intent)
                Common.showToast(getString(R.string.update_enable_install_permission))
                return
            }
        }

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", apkFile)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
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
