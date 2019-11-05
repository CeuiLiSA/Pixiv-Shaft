package ceui.lisa.test

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import ceui.lisa.utils.Common

abstract class BasicActivity : AppCompatActivity() {

    val className: String = javaClass.simpleName + " "
    lateinit var mContext: Context
    lateinit var mActivity: AppCompatActivity


    /**
     * 执行顺序：
     *
     * 1. hideStatusBar()
     *
     * 2. layout()
     *
     * 3. initView()
     *
     * 4. initData()
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Common.showLog(className + "onCreate ")
        mContext = this
        mActivity = this

        if(hideStatusBar()){
            window.statusBarColor = Color.TRANSPARENT
            window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        setContentView(layout())

        initView()
        initData()
    }

    override fun onStart() {
        super.onStart()
        Common.showLog(className + "onStart ")
    }

    override fun onResume() {
        super.onResume()
        Common.showLog(className + "onResume ")
    }

    override fun onPause() {
        super.onPause()
        Common.showLog(className + "onPause ")
    }

    override fun onStop() {
        super.onStop()
        Common.showLog(className + "onStop ")
    }

    override fun onDestroy() {
        super.onDestroy()
        Common.showLog(className + "onDestroy ")
    }

    abstract fun layout(): Int

    open fun hideStatusBar(): Boolean{
        return false
    }

    open fun initView(){

    }

    open fun initData(){

    }
}