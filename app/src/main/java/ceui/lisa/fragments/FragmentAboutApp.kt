package ceui.lisa.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.base.SwipeFragment
import ceui.lisa.databinding.FragmentAboutBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.PackageUtils
import ceui.lisa.utils.Params
import com.scwang.smartrefresh.layout.SmartRefreshLayout


class FragmentAboutApp : SwipeFragment<FragmentAboutBinding>() {

    override fun initLayout() {
        mLayoutID = R.layout.fragment_about
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout {
        return baseBind.refreshLayout
    }

    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }
        baseBind.pixivProblem.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
            intent.putExtra(Params.URL, "https://app.pixiv.help/hc/zh-cn")
            intent.putExtra(Params.TITLE, "常见问题")
            startActivity(intent)
        }
        baseBind.pixivUseDetail.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
            intent.putExtra(Params.URL, "https://www.pixiv.net/terms/?page=term&appname=pixiv_ios")
            intent.putExtra(Params.TITLE, "服务条款")
            startActivity(intent)
        }
        baseBind.pixivPrivacy.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
            intent.putExtra(Params.URL, "https://www.pixiv.net/terms/?page=privacy&appname=pixiv_ios")
            intent.putExtra(Params.TITLE, "隐私政策")
            startActivity(intent)
        }
        baseBind.projectWebsite.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
            intent.putExtra(Params.URL, "https://github.com/CeuiLiSA/Pixiv-Shaft")
            intent.putExtra(Params.TITLE, "项目主页")
            startActivity(intent)
        }
        baseBind.projectLicense.setOnClickListener {
            val intent = Intent(mContext, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "License")
            startActivity(intent)
        }
        baseBind.dontCatchMe.setOnClickListener {
            Common.createDialog(context)
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
            Common.showToast(getString(R.string.string_226))
        }
        baseBind.goQq.setOnClickListener {
            val intent = Intent()
            intent.data = Uri.parse(
                    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + "_RMaPSgL-eB-JZPMFdXGJTSqIqtgCn5G");
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Common.showToast(getString(R.string.string_227))
            }

        }
        baseBind.appVersion.text = Common.getAppVersionName(mContext) + " (" + Common.getAppVersionCode(mContext) + ") "
        baseBind.rateThisApp.setOnClickListener {
//            val manager = ReviewManagerFactory.create(mContext)
//            val request = manager.requestReviewFlow()
//            request.addOnCompleteListener { request ->
//                if (request.isSuccessful) {
//                    // We got the ReviewInfo object
//                    val reviewInfo = request.result
//
//                    val flow = manager.launchReviewFlow(mActivity, reviewInfo)
//                    flow.addOnCompleteListener { _ ->
//                        // The flow has finished. The API does not indicate whether the user
//                        // reviewed or not, or even whether the review dialog was shown. Thus, no
//                        // matter the result, we continue our app flow.
//                    }
//                } else {
//                    // There was some problem, continue regardless of the result.
//                }
//            }
            val uri = Uri.parse("market://details?id=" + mContext.packageName)
            val myAppLinkToMarket = Intent(Intent.ACTION_VIEW, uri)
            try {
                startActivity(myAppLinkToMarket)
            } catch (e: ActivityNotFoundException) {
                Common.showToast("unable to find market app")
            }
        }
    }
}
