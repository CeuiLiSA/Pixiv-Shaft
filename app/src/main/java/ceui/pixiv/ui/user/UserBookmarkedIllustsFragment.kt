package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHomeBinding
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.RefreshState
import ceui.loxia.pushFragment
import ceui.pixiv.PixivFragment
import ceui.pixiv.PixivListViewModel
import ceui.pixiv.pixivListViewModel
import ceui.pixiv.setUpStaggerLayout
import ceui.pixiv.ui.IllustCardActionReceiver
import ceui.pixiv.ui.IllustCardHolder
import ceui.pixiv.ui.works.IllustFragmentArgs
import ceui.refactor.CommonAdapter
import ceui.refactor.ppppx
import ceui.refactor.viewBinding

class UserBookmarkedIllustsFragment: PixivFragment(R.layout.fragment_home) {

    private val binding by viewBinding(FragmentHomeBinding::bind)
    private val viewModel by pixivListViewModel(
        loader = { Client.appApi.getUserBookmarkedIllusts(args.userId) },
        mapper = { illust -> listOf(IllustCardHolder(illust)) }
    )
    private val args by navArgs<UserBookmarkedIllustsFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpStaggerLayout(binding, viewModel)
    }
}