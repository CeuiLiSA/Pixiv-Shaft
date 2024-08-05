package ceui.pixiv.ui.common

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import ceui.lisa.databinding.LayoutToolbarBinding
import ceui.lisa.fragments.ImageFileViewModel
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.launchSuspend
import ceui.refactor.animateFadeInQuickly
import ceui.refactor.animateFadeOutQuickly
import ceui.refactor.setOnClick
import com.bumptech.glide.Glide
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jessyan.progressmanager.ProgressListener
import me.jessyan.progressmanager.ProgressManager
import me.jessyan.progressmanager.body.ProgressInfo
import java.io.File
import java.util.Locale

open class ImgDisplayFragment(layoutId: Int) : PixivFragment(layoutId) {

    protected val viewModel by viewModels<ImageFileViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.fileLiveData.observe(viewLifecycleOwner) { file ->
            if (viewModel.progressLiveData.value != 100) {
                viewModel.progressLiveData.value = 100
            }
            val resolution = getImageDimensions(file)
            Common.showLog("sadasd2 bb ${resolution}")
            Common.showLog("sadasd2 cc ${getFileSize(file)}")
        }
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

    protected fun setUpProgressBar(progressCircular: CircularProgressIndicator) {
        progressCircular.max = 100
        viewModel.progressLiveData.observe(viewLifecycleOwner) { percent ->
            if (percent == -1 || percent == 100) {
                progressCircular.isVisible = false
            } else {
                progressCircular.isVisible = true
                progressCircular.progress = percent
            }
        }
    }

    protected fun setUpFullScreen(
        infoItems: List<View>,
        image: SketchZoomImageView,
        binding: LayoutToolbarBinding
    ) {
        val windowInsetsController = WindowInsetsControllerCompat(
            requireActivity().window,
            requireActivity().window.decorView
        )
        viewModel.isFullscreenMode.observe(viewLifecycleOwner) { isFullScreen ->
            if (isFullScreen) {
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
                    WindowInsetsCompat.CONSUMED
                }
                infoItems.forEach {
                    it.animateFadeOutQuickly()
                }
            } else {
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    binding.toolbarLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        topMargin = insets.top
                    }
                    WindowInsetsCompat.CONSUMED
                }
                infoItems.forEach {
                    it.animateFadeInQuickly()
                }
            }
        }
        binding.naviBack.setOnClick {
            findNavController().popBackStack()
        }
        image.setOnClick {
            viewModel.toggleFullscreen()
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

fun getFileSize(file: File): String {
    val fileSizeInBytes = file.length()

    return when {
        fileSizeInBytes < 1000 -> "${fileSizeInBytes}B" // 小于 1KB
        fileSizeInBytes < 1000 * 1000 -> String.format(
            Locale.getDefault(),
            "%.2f KB",
            fileSizeInBytes / 1000f
        ) // 小于 1MB
        fileSizeInBytes < 1000 * 1000 * 1000 -> String.format(
            Locale.getDefault(),
            "%.2f MB",
            fileSizeInBytes / (1000f * 1000)
        ) // 小于 1GB
        else -> String.format(
            Locale.getDefault(),
            "%.2f GB",
            fileSizeInBytes / (1000f * 1000 * 1000)
        ) // 大于等于 1GB
    }
}