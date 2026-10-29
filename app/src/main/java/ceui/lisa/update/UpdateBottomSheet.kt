package ceui.lisa.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ceui.lisa.BuildConfig
import ceui.lisa.helper.WebLinkHelper
import ceui.pixiv.ui.settings.ChromeTabsPrefs
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.services.appServices
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 「发现新版本」弹窗。**纯订阅者**：自己不下载、不轮询、不持有任何进度，
 * 只把 [ApkDownloadManager.state] 翻译成界面。
 *
 * <p>这样拆开之后「关掉弹窗」不再等于「取消下载」——下载活在进程里，弹窗随时可以再开，
 * 再开时看到的就是当下真实的状态（下载中 / 已暂停 / 已完成）。进程被杀后状态落成
 * 「已暂停」，要用户点一下「继续下载」才接着跑，不会在后台偷偷续。
 */
class UpdateBottomSheet : BottomSheetDialogFragment() {

    private var release: GitHubRelease? = null

    private val downloads by lazy { requireContext().appServices().apkDownloadManager }

    /** 随视图创建 / 销毁；渲染回调一律先取它，拿不到就说明视图已经没了。 */
    private var sheet: UpdateSheetView? = null

    private var pendingInstallFile: File? = null

    /** 主操作当前对应的动作，见 [setPrimary]。 */
    private var lastPrimaryKey: String? = null

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
        private const val ARG_RELEASE = "arg_release"

        /**
         * release 走 arguments 而不是字段：转屏 / 进程回收重建时字段是空的，
         * 弹窗会直接 dismiss —— 正在下载的用户会莫名其妙看到它消失。
         */
        fun newInstance(release: GitHubRelease): UpdateBottomSheet {
            return UpdateBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_RELEASE, Gson().toJson(release))
                }
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

        val rel = readRelease() ?: run { dismiss(); return }
        release = rel
        lastPrimaryKey = null
        val sheet = sheet ?: return

        sheet.bind(BuildConfig.VERSION_NAME, rel, markwonFor(requireContext()))
        sheet.later.setOnClickListener { dismiss() }
        sheet.skip.setOnClickListener {
            AppUpdateChecker.skipVersion(rel.versionName)
            Common.showToast(getString(R.string.update_version_skipped))
            dismiss()
        }

        // 有 sheet 在看的时候下载完成，就不发 banner 了。
        downloads.attachSheet()

        viewLifecycleOwner.lifecycleScope.launch {
            downloads.state.collect { render(it, rel) }
        }

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

    private fun readRelease(): GitHubRelease? {
        val raw = arguments?.getString(ARG_RELEASE) ?: return null
        return runCatching { Gson().fromJson(raw, GitHubRelease::class.java) }.getOrNull()
    }

    // ------------------------------------------------------------ 状态 → 界面

    private fun render(state: ApkDownloadManager.State, rel: GitHubRelease) {
        val sheet = sheet ?: return
        val apk = AppUpdateChecker.findApkAsset(rel)

        // 记录属于别的版本（或者根本没有记录）：这一版就是「还没下」。
        if (state.tag != rel.tagName) {
            sheet.hideProgress()
            if (apk == null) {
                renderOpenInBrowserPrimary(rel)
            } else {
                setPrimary("download") { beginDownload(rel) }
            }
            return
        }

        when (state.phase) {
            ApkDownloadManager.Phase.IDLE -> {
                sheet.hideProgress()
                if (apk == null) {
                    renderOpenInBrowserPrimary(rel)
                } else {
                    setPrimary("download") { beginDownload(rel) }
                }
            }

            ApkDownloadManager.Phase.RUNNING -> {
                sheet.showProgress()
                sheet.setProgress(state.percent, progressLabel(state))
                setPrimary("running", R.string.update_downloading, enabled = false)
            }

            ApkDownloadManager.Phase.MERGING -> {
                sheet.showProgress()
                sheet.setProgress(100, progressLabel(state))
                setPrimary("merging", R.string.update_merging, enabled = false)
            }

            ApkDownloadManager.Phase.PAUSED -> {
                sheet.showProgress()
                sheet.setProgress(state.percent, progressLabel(state))
                setPrimary("continue", R.string.update_continue) { continueDownload(rel) }
            }

            ApkDownloadManager.Phase.FAILED -> {
                sheet.showProgress()
                sheet.setProgress(state.percent, progressLabel(state))
                if (state.resumable) {
                    setPrimary("continue", R.string.update_continue) { continueDownload(rel) }
                } else {
                    setPrimary("retry", R.string.update_retry) { retryDownload(rel) }
                }
            }

            ApkDownloadManager.Phase.COMPLETED -> {
                sheet.showProgress()
                sheet.setProgress(100, "100%")
                setPrimary(
                    key = "install",
                    text = R.string.update_install,
                    icon = R.drawable.ic_file_download_done_24dp,
                ) { installApk() }
            }
        }
    }

    private fun renderOpenInBrowserPrimary(rel: GitHubRelease) {
        val useCct = ChromeTabsPrefs.shouldUseChromeCustomTabs(context)
        val textRes = if (useCct) {
            R.string.open_with_chrome
        } else {
            R.string.webview_handler_open_in_browser
        }
        val key = if (useCct) "open_chrome" else "open_browser"
        setPrimary(
            key = key,
            text = textRes,
            icon = R.drawable.ic_baseline_launch_24,
        ) {
            openInBrowser(rel.htmlUrl ?: releasesUrl())
        }
    }

    /**
     * 主操作只在「动作本身」变化时才重设。进度每秒推 4 次，每次都重建
     * setTextWithIcon 的 span 会白造垃圾，点一下还会把按压态打断。
     */
    private fun setPrimary(
        key: String,
        text: Int = R.string.update_download,
        enabled: Boolean = true,
        icon: Int = R.drawable.ic_file_download_black_24dp,
        onClick: (() -> Unit)? = null,
    ) {
        val sheet = sheet ?: return
        if (lastPrimaryKey == key) return
        lastPrimaryKey = key
        sheet.setPrimary(text, enabled, icon, onClick)
    }

    private fun progressLabel(state: ApkDownloadManager.State): CharSequence {
        val downloaded = formatMb(state.downloadedBytes)
        val total = if (state.totalBytes > 0L) formatMb(state.totalBytes) else "?"
        return when (state.phase) {
            ApkDownloadManager.Phase.RUNNING -> {
                val base = "$downloaded / $total (${state.percent}%)"
                // 分片下载时把「还有几条连接在收」露出来：8 条会随着分片陆续下完一路减到 0，
                // 比一个静止的「8 线程」更能说明多线程真的在跑。
                if (state.activeThreads > 0) {
                    "$base · ${getString(R.string.update_threads, state.activeThreads)}"
                } else {
                    base
                }
            }
            ApkDownloadManager.Phase.MERGING -> getString(R.string.update_merging)
            ApkDownloadManager.Phase.PAUSED ->
                "$downloaded / $total · ${getString(R.string.update_paused)}"
            ApkDownloadManager.Phase.FAILED ->
                if (state.resumable) {
                    "$downloaded / $total · ${getString(R.string.update_interrupted)}"
                } else {
                    getString(R.string.update_resume_unavailable)
                }
            ApkDownloadManager.Phase.COMPLETED -> "100%"
            ApkDownloadManager.Phase.IDLE -> ""
        }
    }

    private fun formatMb(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576f)

    // ------------------------------------------------------------ 动作

    private fun beginDownload(rel: GitHubRelease) {
        if (AppUpdateChecker.findApkAsset(rel) == null) {
            openInBrowser(rel.htmlUrl ?: releasesUrl())
            return
        }
        // 界面不用手动切状态：start 会同步把 state 推到 RUNNING。
        downloads.start(rel)
    }

    private fun continueDownload(rel: GitHubRelease) {
        downloads.start(rel)
    }

    private fun retryDownload(rel: GitHubRelease) {
        downloads.restart(rel)
    }

    private fun installApk() {
        val apkFile = downloads.apkFile()
        if (apkFile == null || !apkFile.exists() || apkFile.length() <= 0L) {
            // 文件被清掉了（用户清理缓存 / 换目录）：当作断点不可用，退回重试。
            release?.let { retryDownload(it) }
            Common.showToast(getString(R.string.update_apk_invalid))
            return
        }
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
        WebLinkHelper.openUrl(context, url)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloads.detachSheet()
        sheet = null
    }
}
