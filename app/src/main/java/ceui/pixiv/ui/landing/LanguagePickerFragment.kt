package ceui.pixiv.ui.landing

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentSelectLanguageBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.lisa.utils.Settings
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.animateFadeInQuickly
import ceui.pixiv.widgets.alertYesOrCancel
import com.blankj.utilcode.util.LanguageUtils
import kotlinx.coroutines.delay
import java.util.Locale

class LanguagePickerFragment : PixivFragment(R.layout.fragment_select_language) {

    private val binding by viewBinding(FragmentSelectLanguageBinding::bind)

    private class VM(initLanguage: String) : ViewModel() {
        val currencyLanguage = MutableLiveData<String>()

        init {
            currencyLanguage.value = initLanguage
        }
    }

    private val viewModel by constructVM({ Shaft.sSettings.appLanguage }) { language -> VM(language) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.welcomeLabel.fadeToNextMessage(getString(R.string.language))

        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.adapter = adapter
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        binding.listView.addItemDecoration(
            BottomDividerDecoration(
                requireContext(),
                R.drawable.list_divider,
            )
        )
        val listView = binding.listView
        adapter.submitList(
            Settings.ALL_LANGUAGE.mapIndexed { index, language ->
                TabCellHolder(
                    language, showGreenDone = true, selected = viewModel.currencyLanguage.map {
                        TextUtils.equals(
                            it, language
                        )
                    }).onItemClick {
                    applyLanguage(index, language)
                }
            }) {
            launchSuspend {
                listView.alpha = 0F
                delay(500L)
                listView.animateFadeInQuickly()
            }
        }

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