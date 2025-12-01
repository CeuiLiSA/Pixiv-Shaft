package ceui.pixiv.ui.prime

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding

class PrimeTagDetailFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val safeArgs by threadSafeArgs<PrimeTagDetailFragmentArgs>()
    private val viewModel by constructVM({ safeArgs.path }) { path ->
        PrimeTagDetailViewModel(path)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        binding.toolbarLayout.naviTitle.text = safeArgs.name
    }

    companion object {
        fun newInstance(name: String, path: String): PrimeTagDetailFragment {
            val fragment = PrimeTagDetailFragment()
            fragment.arguments = PrimeTagDetailFragmentArgs(name, path).toBundle()
            return fragment
        }
    }
}