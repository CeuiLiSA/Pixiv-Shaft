package ceui.lisa.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentAboutBinding
import ceui.lisa.update.AppUpdateChecker
import ceui.lisa.update.GitHubRelease
import ceui.lisa.update.UpdateBottomSheet
import ceui.lisa.utils.Common
import ceui.lisa.utils.PackageUtils
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.dialog.WitDialog.MenuDialogBuilder
import ceui.pixiv.witstudio.theme.WitRowStyle

class FragmentAboutApp : BaseLazyFragment<FragmentAboutBinding>() {

    private var updateJob: Job? = null

    override fun initLayout() {
        mLayoutID = R.layout.fragment_about
    }


    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }

        baseBind.appVersion.text = "%s (%s) "
            .format(Common.getAppVersionName(mContext), Common.getAppVersionCode(mContext))

        if (BuildConfig.IS_LITE) {
            // lite 版没有更新/版本历史两行，版本行独立成单行圆角卡
            baseBind.versionRela.setBackgroundResource(WitRowStyle.rowBackground(0, 1))
        }
        val palette = V3Palette.from(mContext)
        val iconCircle = ColorUtils.blendARGB(
            palette.cardFill, palette.primary, if (palette.isDark) 0.16f else 0.14f
        )
        (baseBind.githubIconWrap.background.mutate() as? GradientDrawable)?.setColor(iconCircle)
        SettingsCatalog.applyThemedRowBg(baseBind.parentLinear)

        if (!BuildConfig.IS_LITE) {
            baseBind.githubUpdateSection.visibility = View.VISIBLE
            baseBind.checkUpdate.setOnClickListener {
                performUpdateCheck(manual = true)
            }
            baseBind.versionHistory.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "版本历史")
                startActivity(intent)
            }
        } else {
            baseBind.githubUpdateSection.visibility = View.GONE
        }

        // Auto-check for github builds
        if (AppUpdateChecker.shouldAutoCheck()) {
            performUpdateCheck(manual = false)
        }

        run {
            baseBind.faq.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Markdown")
                intent.putExtra(Params.URL, "FAQ.md")
                startActivity(intent)
            }

            baseBind.rateThisApp.setOnClickListener {
                try {
                    timber.log.Timber.d("RateThisApp clicked, showing RateAppDialog")
                    ceui.pixiv.widgets.RateAppDialog().show(parentFragmentManager, "RateAppDialog")
                } catch (e: Exception) {
                    timber.log.Timber.e(e, "Failed to show RateAppDialog")
                }
            }
            baseBind.applicationId.text = requireContext().applicationInfo.packageName
            baseBind.goWeibo.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                val weiboInstalled = PackageUtils.isSinaWeiboInstalled(context)
                if (weiboInstalled) {
                    intent.data = Uri.parse("sinaweibo://userinfo?uid=7062240999")
                } else {
                    intent.data = Uri.parse("https://weibo.com/u/7062240999")
                }
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Common.showToast(getString(R.string.msg_no_browser))
                }
            }
            baseBind.goTelegram.setOnClickListener {
                val uri = Uri.parse("https://t.me/joinchat/QBTiWBvo-jda7SEl4VgK-Q")
                val myAppLinkToMarket = Intent(Intent.ACTION_VIEW, uri)
                try {
                    startActivity(myAppLinkToMarket)
                } catch (e: ActivityNotFoundException) {
                    Common.showToast("unable to find market app")
                }
            }
            baseBind.goQq.setOnClickListener {
                // 最新的群在最上面，旧群基本已满；新群在列表头部插一行即可
                // 7 群起 QQ 只给新版短链，拿不到旧版 k，按群号打开群资料卡
                val groups = listOf(
                    getString(R.string.qq_group_8) to
                            "mqqapi://card/show_pslcard?src_type=internal&version=1&card_type=group&source=qrcode&uin=885329323",
                    getString(R.string.qq_group_7) to
                            "mqqapi://card/show_pslcard?src_type=internal&version=1&card_type=group&source=qrcode&uin=996582037",
                    getString(R.string.qq_group_6) to
                            "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3DtBP0SrzxprYrVMadXxJq2KouWxDrcdle",
                    getString(R.string.qq_group_5) to
                            "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3DsWRT0mSWFEiNlkPRtVwK8LmHGStPK9Op",
                    getString(R.string.string_411) to
                            "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D8WwEAkjbS4yOYMtNR17TS-Wghwv8xjNK",
                    getString(R.string.string_387) to
                            "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3DoDdX8b0zEBsZtZF9QNqoTmamW_hTP1By",
                    getString(R.string.string_386) to
                            "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3Dt4_EApMhD08yaYtdTQ40TmrjIx-uuWsk",
                    getString(R.string.string_385) to
                            "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D_4iHqW5v5XkiRxeLKl3hB0me60VVKD9b"
                )
                MenuDialogBuilder(mActivity)
                    .addItems(groups.map { it.first }.toTypedArray()) { dialog, which ->
                        val intent = Intent()
                        intent.data = Uri.parse(groups[which].second)
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            Common.showToast(getString(R.string.string_227))
                        }
                    }
                    .show()
            }
        }

        run {
            baseBind.pixivProblem.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
                intent.putExtra(Params.URL, "https://app.pixiv.help/hc/zh-cn")
                intent.putExtra(Params.TITLE, getString(R.string.pixiv_problem))
                startActivity(intent)
            }
            baseBind.pixivUseDetail.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
                intent.putExtra(
                    Params.URL,
                    "https://www.pixiv.net/terms/?page=term&appname=pixiv_ios"
                )
                intent.putExtra(Params.TITLE, getString(R.string.pixiv_use_detail))
                startActivity(intent)
            }
            baseBind.pixivPrivacy.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
                intent.putExtra(
                    Params.URL,
                    "https://www.pixiv.net/terms/?page=privacy&appname=pixiv_ios"
                )
                intent.putExtra(Params.TITLE, getString(R.string.privacy))
                startActivity(intent)
            }
        }

        run {
            baseBind.projectWebsite.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.data = Uri.parse("https://github.com/CeuiLiSA/Pixiv-Shaft")
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Common.showToast(getString(R.string.msg_no_browser))
                }
            }
        }
    }

    private fun performUpdateCheck(manual: Boolean) {
        baseBind.updateStatus.setText(R.string.update_checking)

        updateJob?.cancel()
        updateJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                AppUpdateChecker.checkForUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                baseBind.updateStatus.setText(R.string.update_check_failed)
                return@launch
            }
            AppUpdateChecker.markChecked()
            when (result) {
                is AppUpdateChecker.UpdateResult.UpdateAvailable -> {
                    val version = result.release.tagName.removePrefix("v").removePrefix("V")
                    baseBind.updateStatus.text = getString(R.string.update_found_new, version)
                    if (!manual && AppUpdateChecker.isVersionSkipped(version)) {
                        return@launch
                    }
                    showUpdateDialog(result.release)
                }

                is AppUpdateChecker.UpdateResult.NoUpdate -> {
                    baseBind.updateStatus.text =
                        getString(R.string.update_already_latest, result.remoteVersion)
                }
            }
        }
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        val dialog = UpdateBottomSheet.newInstance(release)
        dialog.show(childFragmentManager, "update_dialog")
    }

}
