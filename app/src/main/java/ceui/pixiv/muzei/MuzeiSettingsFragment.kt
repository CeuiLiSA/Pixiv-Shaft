package ceui.pixiv.muzei

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSettingsMuzeiBinding
import ceui.lisa.fragments.SettingsPageFragment
import ceui.lisa.utils.Common
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.WitDialog

/**
 * Muzei 来源设置(issue #548):来源单选、R18 开关、立即换一批、Muzei 安装/打开入口。
 * 改来源或 R18 开关后立刻用 replace 模式重拉,旧图不再轮播。
 */
class MuzeiSettingsFragment : SettingsPageFragment<FragmentSettingsMuzeiBinding>() {

    override fun initLayout() {
        mLayoutID = R.layout.fragment_settings_muzei
    }

    override fun initData() {
        val sources = MuzeiSource.entries
        val names = sources.map { getString(it.labelRes) }.toTypedArray()

        baseBind.muzeiSource.text = getString(MuzeiPrefs.source.labelRes)
        baseBind.muzeiSourceRela.setOnClickListener {
            WitDialog.CheckableDialogBuilder(mActivity)
                .setCheckedIndex(sources.indexOf(MuzeiPrefs.source))
                .addItems(names) { dialog, which ->
                    val picked = sources[which]
                    if (picked != MuzeiPrefs.source) {
                        MuzeiPrefs.source = picked
                        baseBind.muzeiSource.text = names[which]
                        reloadIfLoggedIn()
                    }
                    dialog.dismiss()
                }
                .show()
        }

        baseBind.muzeiAllowR18.isChecked = MuzeiPrefs.allowR18
        baseBind.muzeiAllowR18.setOnCheckedChangeListener { _, isChecked ->
            MuzeiPrefs.allowR18 = isChecked
            reloadIfLoggedIn()
        }
        baseBind.muzeiAllowR18Rela.setOnClickListener { baseBind.muzeiAllowR18.performClick() }

        baseBind.muzeiRefreshRela.setOnClickListener {
            if (reloadIfLoggedIn()) {
                Common.showToast(R.string.muzei_refresh_queued)
            }
        }

        bindInstallRow()
    }

    override fun onResume() {
        super.onResume()
        // 从应用商店装完回来,行文案要跟着变
        bindInstallRow()
    }

    private fun bindInstallRow() {
        val installed = MuzeiPrefs.isMuzeiInstalled(mContext)
        baseBind.muzeiInstallTitle.setText(
            if (installed) R.string.muzei_settings_title else R.string.muzei_not_installed
        )
        baseBind.muzeiInstallDesc.setText(
            if (installed) R.string.muzei_installed_desc else R.string.muzei_not_installed_desc
        )
        baseBind.muzeiInstallRela.setOnClickListener {
            val intent = if (installed) {
                mContext.packageManager.getLaunchIntentForPackage(MuzeiPrefs.MUZEI_PACKAGE)
            } else {
                Intent(Intent.ACTION_VIEW, "market://details?id=${MuzeiPrefs.MUZEI_PACKAGE}".toUri())
            } ?: return@setOnClickListener
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // 没有任何应用商店:退到网页版 Play
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "https://play.google.com/store/apps/details?id=${MuzeiPrefs.MUZEI_PACKAGE}".toUri()
                    )
                )
            }
        }
    }

    /** @return 是否真的排了任务(未登录时提示并返回 false) */
    private fun reloadIfLoggedIn(): Boolean {
        if (SessionManager.getBearerTokenOrEmpty().isEmpty()) {
            Common.showToast(R.string.muzei_login_required)
            return false
        }
        MuzeiPrefs.enqueueLoad(mContext, replace = true)
        return true
    }
}
