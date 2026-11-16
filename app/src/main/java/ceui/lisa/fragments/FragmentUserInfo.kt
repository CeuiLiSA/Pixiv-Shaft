package ceui.lisa.fragments

import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.core.text.HtmlCompat
import ceui.lisa.helper.setWebLinks
import ceui.lisa.R
import ceui.lisa.databinding.FragmentUserInfoBinding
import ceui.lisa.interfaces.Display
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params

class FragmentUserInfo : BaseFragment<FragmentUserInfoBinding>(), Display<UserDetailResponse> {

    override fun initLayout() {
        mLayoutID = R.layout.fragment_user_info
    }

    override fun bindView(root: View): FragmentUserInfoBinding = FragmentUserInfoBinding.bind(root)

    public override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener {
            mActivity.finish()
        }
        val user = mActivity.intent.getSerializableExtra(Params.CONTENT) as UserDetailResponse
        invoke(user)
    }

    override fun invoke(response: UserDetailResponse) {
        bindHtmlText(baseBind.mainPage, response.profile.webpage)
        bindHtmlText(baseBind.twitter, response.profile.twitter_url)
        bindHtmlText(baseBind.description, response.user.comment)
        bindHtmlText(baseBind.pawoo, response.profile.pawoo_url)
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
        baseBind.otherText.text = Common.checkEmpty(response.workspace.comment)
    }

    private fun bindHtmlText(view: TextView, raw: String?) {
        val text = Common.checkEmpty(raw)
        view.setWebLinks(text)
    }
}
