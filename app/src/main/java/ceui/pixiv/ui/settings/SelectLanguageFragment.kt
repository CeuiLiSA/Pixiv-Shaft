package ceui.pixiv.ui.settings

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ceui.pixiv.ui.common.PixivFragment
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.lisa.utils.Settings
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.widgets.alertYesOrCancel
import com.blankj.utilcode.util.LanguageUtils
import java.util.Locale

class SelectLanguageFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.language)
        val currencyLanguage = MutableLiveData(Shaft.sSettings.appLanguage)
        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL_TABCELL)
        adapter.submitList(
            Settings.ALL_LANGUAGE.mapIndexed { index, language ->
                TabCellHolder(
                    language,
                    showGreenDone = true,
                    selected = currencyLanguage.map { TextUtils.equals(it, language) }
                ).onItemClick {
                    applyLanguage(index, language)
                }
            }
        )
    }

    private fun applyLanguage(index: Int, rawStr: String) {
        launchSuspend {
            if (alertYesOrCancel(getString(R.string.change_language_confirm, rawStr))) {
                Shaft.sSettings.appLanguage = Settings.ALL_LANGUAGE[index]
                Common.showToast(getString(R.string.string_428), 2)
                Local.setSettings(Shaft.sSettings)
                if (index == 0) {
                    LanguageUtils.applyLanguage(Locale.SIMPLIFIED_CHINESE, true)
                } else if (index == 1) {
                    LanguageUtils.applyLanguage(Locale.JAPAN, true)
                } else if (index == 2) {
                    LanguageUtils.applyLanguage(Locale.US, true)
                } else if (index == 3) {
                    LanguageUtils.applyLanguage(Locale.TRADITIONAL_CHINESE, true)
                } else if (index == 4) {
                    LanguageUtils.applyLanguage(Locale("RU", "ru", ""), true)
                } else if (index == 5) {
                    LanguageUtils.applyLanguage(Locale.KOREA, true)
                }
            }
        }
    }
}