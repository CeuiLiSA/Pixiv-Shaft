package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHomeBinding
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Illust
import ceui.loxia.RefreshState
import ceui.loxia.pushFragment
import ceui.pixiv.ui.IllustCardActionReceiver
import ceui.pixiv.ui.IllustCardHolder
import ceui.pixiv.ui.works.IllustFragmentArgs
import ceui.refactor.CommonAdapter
import ceui.refactor.ppppx

class HomeFragment : Fragment(), IllustCardActionReceiver {

    private var _binding: FragmentHomeBinding? = null

    private val binding get() = _binding!!
    private val viewModel by viewModels<HomeViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.refreshLayout.setOnRefreshListener {
            viewModel.refresh()
        }
        viewModel.refreshState.observe(viewLifecycleOwner) { state ->
            if (state !is RefreshState.LOADING) {
                binding.refreshLayout.finishRefresh()
            }
        }
        binding.listView.addItemDecoration(SpacesItemDecoration(4.ppppx))
        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.listView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.listView.adapter = adapter
        viewModel.obj.observe(viewLifecycleOwner) { obj ->
            adapter.submitList(
                obj.illusts.map { illust ->
                    IllustCardHolder(illust)
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onClickIllustCard(illust: Illust) {
        pushFragment(
            R.id.navigation_illust,
            IllustFragmentArgs(illust.id).toBundle()
        )
    }
}