package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.viewBinding

class FollowingCreatedNovelFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel { DataSource(
        dataFetcher = { Client.appApi.getFollowingCreatedNovels(Params.TYPE_ALL) },
        itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
        filter = { novel -> novel.visible != false }
    ) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.string_197)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
    }
}