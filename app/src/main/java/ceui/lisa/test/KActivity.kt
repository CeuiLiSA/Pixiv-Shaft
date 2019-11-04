package ceui.lisa.test

import android.view.View
import ceui.lisa.R
import ceui.lisa.adapters.UAdapter
import ceui.lisa.interfaces.FullClickListener
import ceui.lisa.utils.Common
import kotlinx.android.synthetic.main.activity_temp.*
import java.util.ArrayList

class KActivity : BasicActivity(){

    override fun layout(): Int {
        return R.layout.activity_temp
    }

    override fun initView() {
        singleText.text = "我被改变了"
        var list: MutableList<UAdapter> = ArrayList()
    }
}