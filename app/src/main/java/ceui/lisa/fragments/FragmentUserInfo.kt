package ceui.lisa.fragments

import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentAboutUserBinding
import ceui.lisa.interfaces.Display
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.utils.Common
import com.scwang.smartrefresh.layout.footer.FalsifyFooter
import com.scwang.smartrefresh.layout.header.FalsifyHeader

class FragmentUserInfo : BaseFragment<FragmentAboutUserBinding>(), Display<UserDetailResponse> {

    override fun initLayout() {
        mLayoutID = R.layout.fragment_about_user
    }

    public override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener {
            mActivity.finish()
        }
        val user = mActivity.intent.getSerializableExtra(
                TemplateActivity.EXTRA_OBJECT) as UserDetailResponse
        invoke(user)
    }

    override fun invoke(response: UserDetailResponse) {
        baseBind.mainPage.setHtml(Common.checkEmpty(response.profile.webpage))
        baseBind.twitter.setHtml(Common.checkEmpty(response.profile.twitter_url))
        baseBind.description.setHtml(Common.checkEmpty(response.user.comment))
        baseBind.pawoo.setHtml(Common.checkEmpty(response.profile.pawoo_url))
        baseBind.computer.text = Common.checkEmpty(response.workspace.pc)
        baseBind.monitor.text = Common.checkEmpty(response.workspace.monitor)
        baseBind.app.text = Common.checkEmpty(response.workspace.tool)
        baseBind.scanner.text = Common.checkEmpty(response.workspace.scanner)
        baseBind.drawBoard.text = Common.checkEmpty(response.workspace.tablet)
        baseBind.mouse.text = Common.checkEmpty(response.workspace.mouse)
        baseBind.printer.text = Common.checkEmpty(response.workspace.printer)
        baseBind.tableObjects.text = Common.checkEmpty(response.workspace.desktop)
        baseBind.likeMusic.text = Common.checkEmpty(response.workspace.music)
        baseBind.table.text = Common.checkEmpty(response.workspace.desk)
        baseBind.chair.text = Common.checkEmpty(response.workspace.chair)
    }

    override fun initView(view: View?) {
        baseBind.refreshLayout.setRefreshHeader(FalsifyHeader(mContext))
        baseBind.refreshLayout.setRefreshFooter(FalsifyFooter(mContext))
    }
}