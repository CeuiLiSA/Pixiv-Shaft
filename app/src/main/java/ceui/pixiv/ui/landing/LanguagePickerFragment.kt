package ceui.pixiv.ui.landing

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentSelectLanguageBinding
import ceui.lisa.feature.HostManager
import ceui.lisa.helper.LanguageHelper
import ceui.lisa.utils.Settings
import ceui.loxia.launchSuspend
import ceui.loxia.openChromeTab
import ceui.loxia.openClashApp
import ceui.loxia.requireNetworkStateManager
import ceui.loxia.threadSafeArgs
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.animateFadeInQuickly
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.alertYesOrCancel
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.delay
import timber.log.Timber

class LanguagePickerFragment : PixivFragment(R.layout.fragment_select_language) {

    private val binding by viewBinding(FragmentSelectLanguageBinding::bind)
    private val safeArgs by threadSafeArgs<LanguagePickerFragmentArgs>()
    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    private class VM(initLanguage: String) : ViewModel() {
        val currencyLanguage = MutableLiveData<String>()

        init {
            currencyLanguage.value = initLanguage
        }
    }

    private val viewModel by constructVM({ Shaft.sSettings.appLanguage }) { language -> VM(language) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomLayout.updatePadding(bottom = insets.bottom + 12.ppppx)
            windowInsets
        }

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

        binding.back.setOnClick {
            findNavController().popBackStack()
        }

        binding.next.setOnClick {
            checkVPNAndNext {

                prefStore.putString(
                    SessionManager.CONTENT_LANGUAGE_KEY,
                    viewModel.currencyLanguage.value
                )

                val baseUrl = if (safeArgs.purpose == LandingFragment.PURPOSE_REGISTER) {
                    HostManager.get().signupUrl
                } else {
                    HostManager.get().loginUrl
                }

                val finalUri = baseUrl.toUri().buildUpon()
                    .appendQueryParameter(
                        "lang",
                        LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage().split("-")[0]
                    )
                    .build()

                Timber.d("fdsdasadsfasd2w2 $finalUri")

                requireContext().openChromeTab(finalUri.toString())
            }
        }
    }


    private fun checkVPNAndNext(block: () -> Unit) {
        val context = requireContext()
        if (requireNetworkStateManager().canAccessGoogle.value == true) {
            block()
        } else {
            launchSuspend {
                if (alertYesOrCancel("请打开VPN后继续")) {
                    openClashApp(context)
                }
            }
        }
    }
}