package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.lisa.view.NovelItemDecoration
import ceui.loxia.Client
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.PvisionCardHolder
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.refactor.ppppx
import ceui.refactor.viewBinding

class UserCreatedNovelFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserCreatedNovelFragmentArgs>()
    private val viewModel by pixivListViewModel { DataSource(
        dataFetcher = { Client.appApi.getUserCreatedNovels(args.userId) },
        itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
        filter = { novel -> novel.visible != false }
    ) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        binding.listView.layoutManager = LinearLayoutManager(requireContext())
        binding.listView.addItemDecoration(NovelItemDecoration(10.ppppx))
    }
}