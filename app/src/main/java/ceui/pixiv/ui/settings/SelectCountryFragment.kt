package ceui.pixiv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.FragmentSelectCountryBinding
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.loxia.RefreshState
import ceui.loxia.setUpHolderRefreshState
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpLayoutManager
import ceui.pixiv.ui.common.setUpToolbar
import ceui.refactor.viewBinding
import com.scwang.smart.refresh.header.FalsifyFooter
import com.scwang.smart.refresh.header.FalsifyHeader
import com.tencent.mmkv.MMKV

class SelectCountryFragment : PixivFragment(R.layout.fragment_select_country), SelectCountryActionReceiver {

    private val binding by viewBinding(FragmentSelectCountryBinding::bind)
    private val viewModel by viewModels<SelectCountryViewModel>()
    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding.toolbarLayout, binding.listView)
        viewModel.loadData(requireContext())
        binding.refreshLayout.setRefreshHeader(FalsifyHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(FalsifyFooter(requireContext()))
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.adapter = adapter
        setUpLayoutManager(binding.listView, ListMode.VERTICAL_NO_MARGIN)
        viewModel.displayList.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list?.map { CountryHolder(it) })
        }
    }

    override fun selectCountry(country: Country) {
        prefStore.putString(SessionManager.CONTENT_LANGUAGE_KEY, country.nameCode)
    }
}


