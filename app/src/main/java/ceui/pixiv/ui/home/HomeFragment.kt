package ceui.pixiv.ui.home

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.setUpStaggerLayout
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.refactor.ppppx
import ceui.refactor.viewBinding

class HomeFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel { HomeDataSource() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
    }
}