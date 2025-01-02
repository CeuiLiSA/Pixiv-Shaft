package ceui.pixiv.ui.task

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding

class TaskStatusFragment : PixivFragment(R.layout.fragment_pixiv_list) {
    private val binding by viewBinding(FragmentPixivListBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding.toolbarLayout, binding.refreshLayout)

    }
}