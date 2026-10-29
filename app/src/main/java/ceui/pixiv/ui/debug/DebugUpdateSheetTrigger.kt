package ceui.pixiv.ui.debug

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.update.AppUpdateChecker
import ceui.lisa.update.GitHubAsset
import ceui.lisa.update.GitHubRelease
import ceui.lisa.update.UpdateBottomSheet
import ceui.lisa.utils.Common
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 【临时·调试】手动触发「更新弹窗」，用于验证无 APK 资产与有 APK 资产时的弹窗形态。
 *
 * 只在 debug 包挂入口（见 [ceui.lisa.fragments.FragmentSettingsExperimental]）。
 */
object DebugUpdateSheetTrigger {

    @JvmStatic
    fun show(fragment: Fragment) {
        val context = fragment.requireContext()
        val items: Array<CharSequence> = arrayOf(
            "测试无 APK 资产形态（验证 Chrome/浏览器按钮）",
            "测试有 APK 资产形态（验证正常下载流程）",
            "拉取线上真实最新 Release",
        )

        WitDialog.MenuDialogBuilder(context)
            .setTitle("调试更新弹窗形态")
            .addItems(items) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showNoApkRelease(fragment)
                    1 -> showWithApkRelease(fragment)
                    2 -> fetchOnlineRelease(fragment)
                }
            }
            .show()
    }

    private fun showNoApkRelease(fragment: Fragment) {
        val mockRelease = GitHubRelease(
            tagName = "v9.9.9-noapk",
            name = "Shaft v9.9.9 测试版（无安装包）",
            body = "### 调试更新说明\n\n- 这是用于测试无直接 APK 下载资产时的形态。\n- 验证开关开启时按钮文案为「使用Chrome打开」，未开启时为「在浏览器中打开」。",
            publishedAt = "2026-10-28T08:00:00Z",
            htmlUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases",
            assets = emptyList(),
        )
        showSheet(fragment, mockRelease)
    }

    private fun showWithApkRelease(fragment: Fragment) {
        val mockRelease = GitHubRelease(
            tagName = "v9.9.9-withapk",
            name = "Shaft v9.9.9 测试版（有安装包）",
            body = "### 调试更新说明\n\n- 包含测试 APK 资产。\n- 主按钮应为「下载更新」。",
            publishedAt = "2026-10-28T08:00:00Z",
            htmlUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases",
            assets = listOf(
                GitHubAsset(
                    name = "shaft-v9.9.9-github.apk",
                    size = 45 * 1024 * 1024L,
                    downloadUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v9.9.9/shaft.apk",
                    contentType = "application/vnd.android.package-archive",
                )
            ),
        )
        showSheet(fragment, mockRelease)
    }

    private fun fetchOnlineRelease(fragment: Fragment) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val release = try {
                AppUpdateChecker.fetchLatestReleaseForTest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Common.showToast("获取线上 release 失败: ${e.message}")
                return@launch
            }
            showSheet(fragment, release)
        }
    }

    private fun showSheet(fragment: Fragment, release: GitHubRelease) {
        if (!fragment.isAdded) return
        val fm = fragment.childFragmentManager
        if (fm.findFragmentByTag("debug_update_sheet") != null) return
        UpdateBottomSheet.newInstance(release).show(fm, "debug_update_sheet")
    }
}
