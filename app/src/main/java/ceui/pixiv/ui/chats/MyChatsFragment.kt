package ceui.pixiv.ui.chats

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMyChatsBinding
import ceui.lisa.databinding.FragmentMyCirclesBinding
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.refactor.viewBinding

class MyChatsFragment : PixivFragment(R.layout.fragment_my_chats), HomeTabContainer {

    private val binding by viewBinding(FragmentMyChatsBinding::bind)
    private val viewModel by pixivValueViewModel {
        Client.webApi.getBookmarkedIllust(SessionManager.loggedInUid)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.result.observe(viewLifecycleOwner) { square ->
            Common.showLog("dsaadsads ${square.body?.total}")
        }
    }
}