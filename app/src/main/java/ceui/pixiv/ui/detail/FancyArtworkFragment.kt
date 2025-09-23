package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import ceui.lisa.R
import ceui.lisa.databinding.FragmentFancyArtworkBinding
import ceui.loxia.requireTaskPool
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpLayoutManager
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenHeight
import timber.log.Timber
import kotlin.math.roundToInt

class FancyArtworkFragment : PixivFragment(R.layout.fragment_fancy_artwork),
    FitsSystemWindowFragment {

    private val binding by viewBinding(FragmentFancyArtworkBinding::bind)
    private val safeArgs by threadSafeArgs<FancyArtworkFragmentArgs>()
    private val viewModel by constructVM({
        safeArgs.illustId to requireTaskPool()
    }) { (illustId, taskPool) ->
        ArtworkViewModel(illustId, taskPool)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top - 10.ppppx
            }
            windowInsets
        }

        binding.headerContent.updateLayoutParams {
            height = (screenHeight * 0.7F).roundToInt()
        }


        setUpLayoutManager(binding.galleryList, ListMode.VERTICAL_NO_MARGIN)
        val galleryAdapter = CommonAdapter(viewLifecycleOwner)
        viewModel.galleryHolders.observe(viewLifecycleOwner) {
            galleryAdapter.submitList(it)
        }
        binding.galleryList.adapter = galleryAdapter


        setUpLayoutManager(binding.artworkListView, ListMode.VERTICAL)

        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.artworkListView.adapter = adapter
        viewModel.holders.observe(viewLifecycleOwner) { holders ->
            adapter.submitList(holders) {
                Timber.d("_remoteDataSyncedEvent adapter submitList: ${viewModel::class.simpleName}")
                viewModel.prepareIdMap(fragmentViewModel.fragmentUniqueId)
            }
        }


    }
}