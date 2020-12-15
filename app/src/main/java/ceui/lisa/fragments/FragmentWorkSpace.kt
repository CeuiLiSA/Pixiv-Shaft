package ceui.lisa.fragments

import android.text.TextUtils
import android.view.View
import android.widget.EditText
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentWorkSpaceBinding
import ceui.lisa.http.NullCtrl
import ceui.lisa.http.Retro
import ceui.lisa.interfaces.Display
import ceui.lisa.models.NullResponse
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.utils.Common
import com.scwang.smartrefresh.layout.SmartRefreshLayout
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

class FragmentWorkSpace: SwipeFragment<FragmentWorkSpaceBinding>(), Display<UserDetailResponse> {

    override fun initLayout() {
        mLayoutID = R.layout.fragment_work_space
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout {
        return baseBind.refreshLayout
    }

    public override fun initData() {
        Retro.getAppApi().getUserDetail(Shaft.sUserModel.response.access_token, Shaft.sUserModel.userId)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(object : NullCtrl<UserDetailResponse>() {
                    override fun success(user: UserDetailResponse) {
                        invoke(user)
                    }

                    override fun must(isSuccess: Boolean) {
                        baseBind.progress.visibility = View.INVISIBLE
                    }
                })
    }

    override fun invoke(response: UserDetailResponse) {
        setText(response.workspace.pc, baseBind.computer)
        setText(response.workspace.monitor, baseBind.monitor)
        setText(response.workspace.tool, baseBind.app)
        setText(response.workspace.scanner, baseBind.scanner)
        setText(response.workspace.tablet, baseBind.drawBoard)
        setText(response.workspace.mouse, baseBind.mouse)
        setText(response.workspace.printer, baseBind.printer)
        setText(response.workspace.desktop, baseBind.tableObjects)
        setText(response.workspace.music, baseBind.likeMusic)
        setText(response.workspace.desk, baseBind.table)
        setText(response.workspace.chair, baseBind.chair)
        setText(response.workspace.comment, baseBind.otherText)
    }

    private fun setText(string: String, view: EditText) {
        try {
            if (!TextUtils.isEmpty(string)) {
                view.setText(string)
            } else {
                view.hint = getString(R.string.no_info)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun initView() {
        baseBind.toolbar.toolbarTitle.text = getString(R.string.string_267)
        baseBind.toolbar.toolbar.setNavigationOnClickListener {
            mActivity.finish()
        }
        Common.animate(baseBind.parentLinear)
        baseBind.submit.setOnClickListener {
            baseBind.progress.visibility = View.VISIBLE
            val map = HashMap<String, String>()

//            pc=我是电脑
//            monitor=我是显示器
//            tool=我是软件
//            scanner=我是扫描仪
//            tablet=我是数位板
//            mouse=我是鼠标
//            printer=我是打印机
//            desktop=桌子东西
//            music=画画音乐
//            desk=桌子
//            chair=椅子
//            comment=其他问题
            map["pc"] = Common.checkEmpty(baseBind.computer)
            map["monitor"] = Common.checkEmpty(baseBind.monitor)
            map["tool"] = Common.checkEmpty(baseBind.app)
            map["scanner"] = Common.checkEmpty(baseBind.scanner)
            map["tablet"] = Common.checkEmpty(baseBind.drawBoard)
            map["mouse"] = Common.checkEmpty(baseBind.mouse)
            map["printer"] = Common.checkEmpty(baseBind.printer)
            map["desktop"] = Common.checkEmpty(baseBind.tableObjects)
            map["music"] = Common.checkEmpty(baseBind.likeMusic)
            map["desk"] = Common.checkEmpty(baseBind.table)
            map["chair"] = Common.checkEmpty(baseBind.chair)
            map["comment"] = Common.checkEmpty(baseBind.otherText)

            Retro.getAppApi().editWorkSpace(Shaft.sUserModel.response.access_token, map)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(object : NullCtrl<NullResponse>() {
                        override fun success(accountEditResponse: NullResponse) {
                            Common.showToast("修改成功！", true)
                            mActivity.finish()
                        }

                        override fun must(isSuccess: Boolean) {
                            baseBind.progress.visibility = View.INVISIBLE
                        }
                    })
        }
    }

}