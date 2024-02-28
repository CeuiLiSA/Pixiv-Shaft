package ceui.loxia

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.GlideApp
import ceui.lisa.databinding.FragmentImageBinding
import ceui.refactor.viewBinding
import com.davemorrissey.labs.subscaleview.ImageSource
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ImageFragment : NavFragment(R.layout.fragment_image) {

    private val binding by viewBinding(FragmentImageBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val image = binding.bigImage
        image.setDoubleTapZoomDuration(250)

        val highQualityUrl = arguments?.getString(highQualityUrl)
        if (highQualityUrl?.isNotEmpty() == true) {
            val progressbar = binding.progressCircularSmall
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = suspendCoroutine { task ->
                    thread {
                        task.resume(
                            try {
                                GlideApp.with(this@ImageFragment)
                                    .asBitmap()
                                    .load(highQualityUrl)
                                    .submit()
                                    .get()
                            } catch (ex: Exception) {
                                null
                            }
                        )
                    }
                }
                progressbar.isVisible = false
                if (bitmap != null) {
                    image.setImage(ImageSource.bitmap(bitmap!!))
                }
            }
        }

        //插画二级详情保持屏幕常亮
        if (Shaft.sSettings.isIllustDetailKeepScreenOn) {
            binding.root.keepScreenOn = true
        }
    }

    companion object {
        const val highQualityUrl = "ImageFragment.highQualityUrl"

        fun newInstance(
            highQualityUrlParam: String
        ): ImageFragment {
            return ImageFragment().apply {
                arguments = bundleOf(
                    highQualityUrl to highQualityUrlParam
                )
            }
        }
    }
}