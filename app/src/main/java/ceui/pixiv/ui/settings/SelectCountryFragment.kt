package ceui.pixiv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.loxia.RefreshState
import ceui.loxia.setUpHolderRefreshState
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.refactor.viewBinding
import com.tencent.mmkv.MMKV

class SelectCountryFragment : PixivFragment(R.layout.fragment_pixiv_list), SelectCountryActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by viewModels<SelectCountryViewModel>()
    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.loadData(requireContext())
        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL_NO_MARGIN)
        viewModel.displayList.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list?.map { CountryHolder(it) })
        }
    }

    override fun selectCountry(country: Country) {
        prefStore.putString(SessionManager.CONTENT_LANGUAGE_KEY, country.nameCode)
    }
}


