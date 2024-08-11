package ceui.pixiv.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import ceui.lisa.databinding.LayoutToolbarBinding
import ceui.lisa.utils.Common
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.getHumanReadableMessage
import ceui.loxia.observeEvent
import ceui.pixiv.ui.task.LoadTask
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskStatus
import ceui.pixiv.ui.works.PagedImgActionReceiver
import ceui.pixiv.ui.works.ViewPagerViewModel
import ceui.pixiv.ui.works.ToggleToolnarViewModel
import ceui.refactor.animateFadeInQuickly
import ceui.refactor.animateFadeOutQuickly
import ceui.refactor.setOnClick
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale



open class ImgDisplayViewModel : ToggleToolnarViewModel() {

    private val _taskMap: HashMap<Int, LoadTask> = hashMapOf()

    protected fun taskFactory(index: Int, namedUrl: NamedUrl, context: Context): LoadTask {
        return _taskMap.getOrPut(index) {
            LoadTask(namedUrl, context)
        }
    }

    fun loadNamedUrl(namedUrl: NamedUrl, context: Context): LoadTask {
        val task = taskFactory(0, namedUrl, context)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                task.execute()
            }
        }
        return task
    }
}


abstract class ImgDisplayFragment(layoutId: Int) : PixivFragment(layoutId) {

    protected val viewModel by viewModels<ImgDisplayViewModel>()
    private val viewPagerViewModel by viewModels<ViewPagerViewModel>(ownerProducer = { requireParentFragment() })

    abstract val downloadButton: View
    abstract val progressCircular: CircularProgressIndicator
    abstract val displayImg: SketchZoomImageView
    abstract fun displayName(): String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        progressCircular.max = 100
        displayImg.setOnClick {
            if (parentFragment is ViewPagerFragment) {
                it.findActionReceiverOrNull<PagedImgActionReceiver>()?.onClickPagedImg()
            } else {
                viewModel.toggleFullscreen()
            }
        }
    }

    protected open fun setUpLoadTask(context: Context, task: LoadTask) {
        task.file.observe(viewLifecycleOwner) { file ->
            displayImg.loadImage(file)
            downloadButton.setOnClick {
                saveImageToGallery(context, file, displayName())
            }
            val resolution = getImageDimensions(file)
            Common.showLog("sadasd2 bb ${resolution}")
            Common.showLog("sadasd2 cc ${getFileSize(file)}")
        }
        if (parentFragment is ViewPagerFragment) {
            viewPagerViewModel.downloadEvent.observeEvent(viewLifecycleOwner) { index ->
                task.file.value?.let { file ->
                    saveImageToGallery(context, file, displayName())
                }
            }
        }
        progressCircular.setUpWithTaskStatus(task.status, viewLifecycleOwner)
    }
}


fun Fragment.setUpFullScreen(
    viewModel: ToggleToolnarViewModel,
    infoItems: List<View>,
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

fun CircularProgressIndicator.setUpWithTaskStatus(
    taskStatus: LiveData<TaskStatus>,
    lifecycleOwner: LifecycleOwner
) {
    val progressCircular = this
    taskStatus.observe(lifecycleOwner) { status ->
        if (status is TaskStatus.NotStart) {
            progressCircular.isVisible = true
            progressCircular.progress = 0
        } else if (status is TaskStatus.Executing) {
            progressCircular.isVisible = true
            progressCircular.progress = status.percentage
        } else {
            progressCircular.isVisible = false
        }
    }
}

fun CircularProgressIndicator.setUpWithTaskStatus(
    taskStatus: LiveData<TaskStatus>,
    errorLayout: ViewGroup,
    errorTitle: TextView,
    retryButton: TextView,
    errorRetry: () -> Unit,
    lifecycleOwner: LifecycleOwner
) {
    val progressCircular = this
    taskStatus.observe(lifecycleOwner) { status ->
        if (status is TaskStatus.NotStart) {
            progressCircular.isVisible = true
            progressCircular.progress = 0
        } else if (status is TaskStatus.Executing) {
            progressCircular.isVisible = true
            progressCircular.progress = status.percentage
        } else {
            progressCircular.isVisible = false
        }
        errorLayout.isVisible = status is TaskStatus.Error
        retryButton.setOnClick {
            errorRetry()
        }
        if (status is TaskStatus.Error) {
            errorTitle.text = status.exception.getHumanReadableMessage(context)
        }
    }
}