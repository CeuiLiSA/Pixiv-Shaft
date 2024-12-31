package ceui.lisa.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentAboutBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.PackageUtils
import ceui.lisa.utils.Params
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MenuDialogBuilder
import com.scwang.smart.refresh.layout.SmartRefreshLayout

class FragmentAboutApp : SwipeFragment<FragmentAboutBinding>() {

    override fun initLayout() {
        mLayoutID = R.layout.fragment_about
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout {
        return baseBind.refreshLayout
    }

    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }

        baseBind.appVersion.text = "%s (%s) "
            .format(Common.getAppVersionName(mContext), Common.getAppVersionCode(mContext))

        run {
            baseBind.faq.setOnClickListener{
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Markdown")
                intent.putExtra(Params.URL, "FAQ.md")
                startActivity(intent)
            }

            baseBind.rateThisApp.setOnClickListener {
                val uri = Uri.parse("market://details?id=" + mContext.packageName)
                val myAppLinkToMarket = Intent(Intent.ACTION_VIEW, uri)
                try {
                    startActivity(myAppLinkToMarket)
                } catch (e: ActivityNotFoundException) {
                    Common.showToast("unable to find market app")
                }
            }
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
                startActivity(intent)
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
                val choices = arrayOf(getString(R.string.string_385), getString(R.string.string_386), getString(R.string.string_387), getString(R.string.string_411), getString(R.string.qq_group_5), getString(R.string.qq_group_6))
                MenuDialogBuilder(mActivity)
                    .addItems(choices) { dialog, which ->
                        val intent = Intent()
                        when (which) {
                            0 -> {
                                intent.data = Uri.parse(
                                    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + "_4iHqW5v5XkiRxeLKl3hB0me60VVKD9b"
                                )
                            }
                            1 -> {
                                intent.data = Uri.parse(
                                    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + "t4_EApMhD08yaYtdTQ40TmrjIx-uuWsk"
                                )
                            }
                            2 -> {
                                intent.data = Uri.parse(
                                    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + "oDdX8b0zEBsZtZF9QNqoTmamW_hTP1By"
                                )
                            }
                            3 -> {
                                intent.data = Uri.parse(
                                    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + "8WwEAkjbS4yOYMtNR17TS-Wghwv8xjNK"
                                )
                            }
                            4 -> {
                                intent.data = Uri.parse(
                                    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + "sWRT0mSWFEiNlkPRtVwK8LmHGStPK9Op"
                                )
                            }
                            5 -> {
                                intent.data = Uri.parse(
                                    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + "tBP0SrzxprYrVMadXxJq2KouWxDrcdle"
                                )
                            }
                        }
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            Common.showToast(getString(R.string.string_227))
                        }
                    }
                    .setSkinManager(QMUISkinManager.defaultInstance(mActivity))
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
                intent.putExtra(Params.URL, "https://www.pixiv.net/terms/?page=term&appname=pixiv_ios")
                intent.putExtra(Params.TITLE, getString(R.string.pixiv_use_detail))
                startActivity(intent)
            }
            baseBind.pixivPrivacy.setOnClickListener {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
                intent.putExtra(Params.URL,"https://www.pixiv.net/terms/?page=privacy&appname=pixiv_ios")
                intent.putExtra(Params.TITLE, getString(R.string.privacy))
                startActivity(intent)
            }
        }

        run {
            baseBind.dontCatchMe.setOnClickListener {
                Common.createDialog(context)
            }

            baseBind.projectWebsite.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.data = Uri.parse("https://github.com/CeuiLiSA/Pixiv-Shaft")
                startActivity(intent)
            }
        }
    }
}
