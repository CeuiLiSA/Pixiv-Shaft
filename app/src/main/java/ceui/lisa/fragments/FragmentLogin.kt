package ceui.lisa.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.UserEntity
import ceui.lisa.databinding.ActivityLoginBinding
import ceui.lisa.interfaces.FeedBack
import ceui.lisa.models.UserModel
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.lisa.utils.Dev
import ceui.lisa.utils.Local
import ceui.lisa.utils.Params
import com.facebook.rebound.Spring
import com.facebook.rebound.SpringConfig
import com.facebook.rebound.SpringSystem
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog.MessageDialogBuilder

class LandingViewModel : ViewModel() {

    val isChecked = MutableLiveData(false)
}

class FragmentLogin : BaseFragment<ActivityLoginBinding>() {

    private val viewModel: LandingViewModel by viewModels()
    private val springSystem = SpringSystem.create()
    private var rotate: Spring? = null
    private var mHitCountDown //
            = 0
    private var mHitToast: Toast? = null
    override fun onResume() {
        super.onResume()
        mHitCountDown = TAPS_TO_BE_A_DEVELOPER
    }

    public override fun initLayout() {
        mLayoutID = R.layout.activity_login
    }

    public override fun initView() {
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0)
        baseBind.toolbar.inflateMenu(R.menu.login_menu)
        baseBind.toolbar.setOnMenuItemClickListener(Toolbar.OnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "设置")
                startActivity(intent)
                return@OnMenuItemClickListener true
            } else if (item.itemId == R.id.action_import) {
                val userJson = ClipBoardUtils.getClipboardContent(mContext)
                if (userJson != null && !TextUtils.isEmpty(userJson)
                    && userJson.contains(Params.USER_KEY)
                ) {
                    performLogin(userJson)
                } else {
                    Common.showToast("剪贴板无用户信息", 3)
                }
                return@OnMenuItemClickListener true
            }
            false
        })
        baseBind.firstText.movementMethod = LinkMovementMethod.getInstance()
        val matchTOS = getString(R.string.terms_of_service)
        val matchPP = getString(R.string.privacy_policy)
        val terms = String.format(getString(R.string.landing_terms_base), matchTOS, matchPP)
        baseBind.firstText.text = SpannableString(terms).apply {
            this.setLinkSpan(matchTOS, hideUnderLine = false) {
                val intent = Intent(mContext, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
                intent.putExtra(
                    Params.URL,
                    "https://www.pixiv.net/terms/?page=term&appname=pixiv_ios"
                )
                intent.putExtra(Params.TITLE, getString(R.string.pixiv_use_detail))
                startActivity(intent)
            }
            this.setLinkSpan(matchPP, hideUnderLine = false) {
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
        viewModel.isChecked.observe(this) {
            baseBind.checkboxOne.isSelected = it
        }

        baseBind.checkboxOne.setOnClickListener {
            val res = viewModel.isChecked.value ?: false
            viewModel.isChecked.value = !res
        }
    }

    private fun performLogin(userJson: String) {
        val exportUser = Shaft.sGson.fromJson(userJson, UserModel::class.java)
        Local.saveUser(exportUser)
        Dev.refreshUser = true
        val userEntity = UserEntity()
        userEntity.loginTime = System.currentTimeMillis()
        userEntity.userID = exportUser.user.id
        userEntity.userGson = Shaft.sGson.toJson(Local.getUser())
        AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity)
        Common.showToast("导入成功", 2)
        val intent = Intent(mContext, MainActivity::class.java)
        MainActivity.newInstance(intent, mContext)
        mActivity.finish()
    }

    private fun openOAuthTab(url: String) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(requireContext(), Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            Common.showToast("未找到浏览器")
        }
    }

    private fun openProxyHint(feedBack: FeedBack) {
        val qmuiDialog = MessageDialogBuilder(mContext)
            .setTitle(mContext.getString(R.string.string_143))
            .setMessage(mContext.getString(R.string.string_360))
            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
            .addAction(mContext.getString(R.string.cancel)) { dialog, index -> dialog.dismiss() }
            .addAction(mContext.getString(R.string.string_361)) { dialog, index ->
                feedBack.doSomething()
                dialog.dismiss()
            }
            .create()
        val window = qmuiDialog.window
        window?.setWindowAnimations(R.style.dialog_animation_scale)
        qmuiDialog.show()
    }

    override fun initData() {
        rotate = springSystem.createSpring()
        rotate?.springConfig = SpringConfig.fromOrigamiTensionAndFriction(15.0, 8.0)
    }

    private fun checkAndNext(block: () -> Unit) {
        if (viewModel.isChecked.value == true) {
            block()
        } else {
            Toast.makeText(requireContext(), getString(R.string.read_agreement), Toast.LENGTH_SHORT)
                .show()
        }
    }

    companion object {
        private const val TAPS_TO_BE_A_DEVELOPER = 7
    }
}

fun SpannableString.setLinkSpan(
    text: String,
    hideUnderLine: Boolean = true,
    color: Int? = null,
    action: () -> Unit
) {
    val textIndex = this.indexOf(text)
    if (textIndex >= 0) {
        setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    action()
                }

                override fun updateDrawState(ds: TextPaint) {
                    color?.let {
                        ds.linkColor = it
                    }
                    if (hideUnderLine) {
                        ds.color = ds.linkColor
                        ds.isUnderlineText = false
                    } else {
                        super.updateDrawState(ds)
                    }
                }
            },
            textIndex,
            textIndex + text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}
