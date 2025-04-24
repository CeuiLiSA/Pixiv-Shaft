package ceui.pixiv.ui.landing

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.FragmentPixivListBinding.bind
import ceui.lisa.databinding.FragmentSelectLanguageBinding
import ceui.lisa.utils.Settings
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.animateFadeIn
import ceui.pixiv.utils.animateFadeInQuickly
import ceui.pixiv.utils.setOnClick
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.delay

class LanguagePickerFragment : PixivFragment(R.layout.fragment_select_language) {

    private val binding by viewBinding(FragmentSelectLanguageBinding::bind)
    private val prefStore by lazy { MMKV.defaultMMKV() }

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
                    }).onItemClick { viewModel.currencyLanguage.value = language }
            }) {
            launchSuspend {
                listView.alpha = 0F
                delay(500L)
                listView.animateFadeInQuickly()
            }
        }


        binding.start.setOnClick {
//            pushFragment(R.id.navigation_select_login_way)
            val navController = findNavController()

            val navOptions = NavOptions.Builder()
                // 清除栈中的 A, B, C
                .setPopUpTo(R.id.navigation_landing, true) // 设定返回到 A 并移除它以及之后的 fragment
                .build()

            SessionManager.markLandingPageShown()

            // 跳转到 X Fragment，且关闭之前的 fragment
            navController.navigate(R.id.navigation_discover_all, null, navOptions)
        }
    }
}