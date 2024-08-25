package ceui.pixiv.ui.chats

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentMyChatsBinding
import ceui.lisa.databinding.FragmentMyCirclesBinding
import ceui.pixiv.ui.common.HomeTabContainer
import ceui.pixiv.ui.common.PixivFragment
import ceui.refactor.viewBinding

class MyChatsFragment : PixivFragment(R.layout.fragment_my_chats), HomeTabContainer {

    private val binding by viewBinding(FragmentMyChatsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }
}