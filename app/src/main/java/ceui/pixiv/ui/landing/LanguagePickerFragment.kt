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
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.FragmentPixivListBinding.bind
import ceui.lisa.databinding.FragmentSelectLanguageBinding
import ceui.lisa.utils.Settings
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding

class LanguagePickerFragment : PixivFragment(R.layout.fragment_select_language) {

    private val binding by viewBinding(FragmentSelectLanguageBinding::bind)

    class VM(initLanguage: String) : ViewModel() {
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

        adapter.submitList(
            Settings.ALL_LANGUAGE.mapIndexed { index, language ->
                TabCellHolder(
                    language, showGreenDone = true, selected = viewModel.currencyLanguage.map {
                        TextUtils.equals(
                            it, language
                        )
                    }).onItemClick { viewModel.currencyLanguage.value = language }
            })
    }
}