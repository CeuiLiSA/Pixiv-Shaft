package ceui.pixiv.ui.common

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentImgUrlBinding
import ceui.lisa.fragments.ImageFileViewModel
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.launchSuspend
import ceui.refactor.setOnClick
import ceui.refactor.viewBinding
import com.bumptech.glide.Glide
import com.github.panpf.sketch.loadImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jessyan.progressmanager.ProgressListener
import me.jessyan.progressmanager.ProgressManager
import me.jessyan.progressmanager.body.ProgressInfo
import java.io.File

class ImgUrlFragment : PixivFragment(R.layout.fragment_img_url) {

    private val binding by viewBinding(FragmentImgUrlBinding::bind)
    private val args by navArgs<ImgUrlFragmentArgs>()
    protected val viewModel by viewModels<ImageFileViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        val context = requireContext()
        viewModel.fileLiveData.observe(viewLifecycleOwner) { file ->
            if (viewModel.progressLiveData.value != 100) {
                viewModel.progressLiveData.value = 100
            }
            val resolution = getImageDimensions(file)
            Common.showLog("sadasd2 aa ${args.url}")
            Common.showLog("sadasd2 bb ${resolution}")
            binding.image.loadImage(file)
            binding.download.setOnClick {
                saveImageToGallery(context, file, args.displayName)
            }
        }

        binding.progressCircular.max = 100
        viewModel.progressLiveData.observe(viewLifecycleOwner) { percent ->
            if (percent == -1 || percent == 100) {
                binding.progressCircular.isVisible = false
            } else {
                binding.progressCircular.isVisible = true
                binding.progressCircular.progress = percent
            }
        }

        prepareOriginalImage(args.url)


    }

    protected fun prepareOriginalImage(url: String?) {
        if (url.isNullOrEmpty()) {
            return
        }

        val frag = this

        ProgressManager.getInstance()
            .addResponseListener(url, object : ProgressListener {
                override fun onProgress(progressInfo: ProgressInfo) {
                    viewModel.progressLiveData.value = progressInfo.percent
                    if (progressInfo.isFinish) {
                        ProgressManager.getInstance().removeResponseListener(
                            url,
                            this
                        )
                    }
                }

                override fun onError(id: Long, e: Exception) {
                    viewModel.progressLiveData.value = -1
                }
            })

        launchSuspend {
            withContext(Dispatchers.IO) {
                try {
                    val file = Glide.with(frag)
                        .asFile()
                        .load(GlideUrlChild(url))
                        .submit()
                        .get()
                    viewModel.isHighQualityImageLoaded = true
                    viewModel.fileLiveData.postValue(file)
                } catch (ex: Exception) {
                    viewModel.progressLiveData.value = -1
                    throw ex
                }
            }
        }
    }

    fun getImageDimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            // 设置为 true 只解析图片的宽高，不加载图片到内存中
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return Pair(options.outWidth, options.outHeight)
    }
}