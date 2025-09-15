package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.databinding.FragmentArtworkViewpagerBinding
import ceui.loxia.ObjectType
import ceui.loxia.threadSafeArgs
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.novel.NovelTextFragment
import ceui.pixiv.ui.novel.NovelTextFragmentArgs
import ceui.pixiv.widgets.setupVerticalAwareViewPager2
import timber.log.Timber


class ArtworkViewPagerFragment : PixivFragment(R.layout.fragment_artwork_viewpager),
    ViewPagerFragment {

    private val binding by viewBinding(FragmentArtworkViewpagerBinding::bind)
    private val safeArgs by threadSafeArgs<ArtworkViewPagerFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ids = ArtworksMap.store[safeArgs.seed]
        setupVerticalAwareViewPager2(binding.artworkViewpager)
        Timber.d("ArtworkViewPagerFragment seed: ${safeArgs.seed}")
        if (ids?.isNotEmpty() == true) {
            binding.artworkViewpager.adapter = object : FragmentStateAdapter(this) {
                override fun createFragment(position: Int): Fragment {
                    if (safeArgs.objectType == ObjectType.NOVEL) {
                        return NovelTextFragment().apply {
                            arguments = NovelTextFragmentArgs(ids[position]).toBundle()
                        }
                    } else {
                        return ArtworkFragment().apply {
                            arguments = ArtworkFragmentArgs(ids[position]).toBundle()
                        }
                    }
                }

                override fun getItemCount(): Int {
                    return ids.size
                }
            }
            val index = ids.indexOf(safeArgs.objectId)
            if (index > 0) {
                binding.artworkViewpager.setCurrentItem(index, false)
            }
        } else {
            binding.artworkViewpager.adapter = object : FragmentStateAdapter(this) {
                override fun createFragment(position: Int): Fragment {
                    if (safeArgs.objectType == ObjectType.NOVEL) {
                        return NovelTextFragment().apply {
                            arguments = NovelTextFragmentArgs(safeArgs.objectId).toBundle()
                        }
                    } else {
                        return ArtworkFragment().apply {
                            arguments = ArtworkFragmentArgs(safeArgs.objectId).toBundle()
                        }
                    }
                }

                override fun getItemCount(): Int {
                    return 1
                }
            }
        }
    }
}