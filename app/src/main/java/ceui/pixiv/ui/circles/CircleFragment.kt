package ceui.pixiv.ui.circles

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentCircleBinding
import ceui.loxia.Client
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.refactor.viewBinding

class CircleFragment : PixivFragment(R.layout.fragment_circle) {

    private val binding by viewBinding(FragmentCircleBinding::bind)
    private val viewModel by pixivValueViewModel {
        Client.webApi.getCircleDetail("イラスト")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }
}