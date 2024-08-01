package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.pixiv.PixivFragment
import ceui.pixiv.pixivListViewModel
import ceui.pixiv.setUpStaggerLayout
import ceui.pixiv.ui.IllustCardHolder
import ceui.refactor.viewBinding

class UserBookmarkedIllustsFragment: PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
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