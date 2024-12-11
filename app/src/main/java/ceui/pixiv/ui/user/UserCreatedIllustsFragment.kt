package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.UserResponse
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.bottom.OffsetPageActionReceiver
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState

import ceui.pixiv.widgets.DialogViewModel
import ceui.refactor.viewBinding

class UserCreatedIllustsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserCreatedIllustsFragmentArgs>()
    private val viewModel by pixivListViewModel {
        DataSource(
            dataFetcher = { Client.appApi.getUserCreatedIllusts(args.userId, args.objectType) },
            itemMapper = { illust -> listOf(IllustCardHolder(illust)) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
    }
}