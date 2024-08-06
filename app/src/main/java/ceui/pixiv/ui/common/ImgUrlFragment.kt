package ceui.pixiv.ui.common

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentImgUrlBinding
import ceui.lisa.utils.Common
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView

class ImgUrlFragment : ImgDisplayFragment(R.layout.fragment_img_url) {

    private val binding by viewBinding(FragmentImgUrlBinding::bind)
    private val args by navArgs<ImgUrlFragmentArgs>()
    override val downloadButton: View
        get() = binding.download
    override val displayImg: SketchZoomImageView
        get() = binding.image

    override fun displayName(): String {
        return args.displayName
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpFullScreen(
            listOf(
                binding.download,
                binding.toolbarLayout.root,
                binding.topShadow,
                binding.bottomShadow
            ),
            binding.image,
            binding.toolbarLayout
        )
        setUpProgressBar(binding.progressCircular)
        prepareOriginalImage(args.url)
    }
}
