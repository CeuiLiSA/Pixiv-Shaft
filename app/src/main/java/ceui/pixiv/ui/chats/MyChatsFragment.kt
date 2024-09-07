package ceui.pixiv.ui.chats

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMyChatsBinding
import ceui.lisa.databinding.FragmentMyCirclesBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.ObjectType
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.CommonViewPagerViewModel
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.refactor.viewBinding

class MyChatsFragment : TitledViewPagerFragment(R.layout.fragment_pixiv_list), HomeTabContainer {

    private val binding by viewBinding(FragmentPixivListBinding::bind)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }
}