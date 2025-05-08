package ceui.pixiv.ui.circles

import android.os.Bundle
import android.view.View
import androidx.lifecycle.map
import ceui.lisa.R
import ceui.lisa.databinding.FragmentCircleInfoBinding
import ceui.loxia.Client
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.repo.RemoteRepository
import ceui.pixiv.ui.common.viewBinding

class CircleInfoFragment : PixivFragment(R.layout.fragment_circle_info) {

    private val binding by viewBinding(FragmentCircleInfoBinding::bind)
    private val viewModel by pixivValueViewModel(
        ownerProducer = { requireParentFragment() },
        repositoryProducer = {
            RemoteRepository {
                Client.webApi.getCircleDetail("aa")
            }
        })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.circle = viewModel.result.map { it.data }
    }
}