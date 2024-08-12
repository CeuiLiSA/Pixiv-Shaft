package ceui.loxia

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.GlideApp
import ceui.lisa.databinding.FragmentImageBinding
import ceui.lisa.fragments.ImageFileViewModel
import ceui.refactor.viewBinding
import com.github.panpf.sketch.loadImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


class ImageFragment : NavFragment(R.layout.fragment_image) {

    private val binding by viewBinding(FragmentImageBinding::bind)
    private val fileViewModel by viewModels<ImageFileViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fileViewModel.fileLiveData.observe(viewLifecycleOwner) { file ->
            binding.bigImage.loadImage(file)
        }
        val progressbar = binding.progressCircularSmall

        val scope = this

        val highQualityUrl = arguments?.getString(highQualityUrl)
        if (highQualityUrl?.isNotEmpty() == true) {
            launchSuspend {
                withContext(Dispatchers.IO) {
                    try {
                        val file = GlideApp.with(scope)
                            .asFile()
                            .load(highQualityUrl)
                            .submit()
                            .get()
                        withContext(Dispatchers.Main) {
                            progressbar.isVisible = false
                        }
                        fileViewModel.fileLiveData.postValue(file)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
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