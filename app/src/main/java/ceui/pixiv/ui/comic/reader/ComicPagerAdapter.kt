package ceui.pixiv.ui.comic.reader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.databinding.CellComicPageBinding
import ceui.pixiv.ui.task.LoadTask
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import ceui.pixiv.ui.task.TaskStatus
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.view.zoom.OnViewLongPressListener
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import com.github.panpf.zoomimage.zoom.ContentScaleCompat
import timber.log.Timber
import java.io.File

/**
 * 漫画页面适配器：依赖倒置后只接 [LifecycleOwner] 与函数引用，不再引用 Fragment / Settings。
 *
 * 职责单一：把 [ComicReaderV3ViewModel.ComicPage] 渲染到 [com.github.panpf.zoomimage.SketchZoomImageView]。
 * 加载链路：URL 由 [urlResolver] 决定 → TaskPool 下载 → image.loadImage(file)。
 *
 * 业务规则（preview vs original / fitMode 映射）由调用方注入，符合单向依赖。
 */
class ComicPagerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val urlResolver: (ComicReaderV3ViewModel.ComicPage) -> String,
    private val contentScaleProvider: () -> ContentScaleCompat,
    private val onSingleTap: (TapZone) -> Unit,
    private val onLongPressPage: ((Int) -> Unit)? = null,
) : ListAdapter<ComicReaderV3ViewModel.ComicPage, ComicPagerAdapter.PageHolder>(DIFF) {

    enum class TapZone { Left, Center, Right }

    var fillHeight: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val binding = CellComicPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.image.layoutParams = binding.image.layoutParams.apply {
            height = if (fillHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        return PageHolder(lifecycleOwner, binding, urlResolver, contentScaleProvider, onSingleTap, onLongPressPage)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.clearObservers()
    }

    class PageHolder(
        private val lifecycleOwner: LifecycleOwner,
        val binding: CellComicPageBinding,
        private val urlResolver: (ComicReaderV3ViewModel.ComicPage) -> String,
        private val contentScaleProvider: () -> ContentScaleCompat,
        private val onSingleTap: (TapZone) -> Unit,
        private val onLongPressPage: ((Int) -> Unit)?,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentTask: LoadTask? = null
        private var resultObserver: Observer<File?>? = null
        private var statusObserver: Observer<TaskStatus>? = null

        init {
            // OffsetCompat 是 Kotlin value class，SAM lambda 不能转换成 mangled 方法名，
            // 必须用 object expression 显式覆盖。
            binding.image.onViewTapListener = object : OnViewTapListener {
                override fun onViewTap(view: android.view.View, touchPoint: OffsetCompat) {
                    val w = binding.image.width
                    val zone = when {
                        w <= 0 -> TapZone.Center
                        touchPoint.x < w / 3f -> TapZone.Left
                        touchPoint.x > w * 2f / 3f -> TapZone.Right
                        else -> TapZone.Center
                    }
                    onSingleTap(zone)
                }
            }
            binding.image.onViewLongPressListener = object : OnViewLongPressListener {
                override fun onViewLongPress(view: android.view.View, touchPoint: OffsetCompat) {
                    val current = binding.root.tag as? ComicReaderV3ViewModel.ComicPage
                    if (current != null) onLongPressPage?.invoke(current.index)
                }
            }
            binding.reload.setOnClickListener {
                val current = binding.root.tag as? ComicReaderV3ViewModel.ComicPage ?: return@setOnClickListener
                TaskPool.removeTask(urlResolver(current))
                bind(current)
            }
        }

        fun bind(page: ComicReaderV3ViewModel.ComicPage) {
            binding.root.tag = page
            binding.image.zoomable.contentScaleState.value = contentScaleProvider()

            clearObservers()
            binding.reload.visibility = View.GONE
            binding.progress.visibility = View.VISIBLE

            val url = urlResolver(page)
            val task = TaskPool.getLoadTask(NamedUrl("", url))
            currentTask = task
            task.result.value?.let { binding.image.loadImage(it) }

            val rObs = Observer<File?> { file ->
                if (file == null) return@Observer
                Timber.d("[ComicPager] page=${page.index} file ready size=${file.length()}")
                binding.image.loadImage(file)
                binding.progress.visibility = View.GONE
                binding.reload.visibility = View.GONE
            }
            val sObs = Observer<TaskStatus> { status ->
                when (status) {
                    is TaskStatus.Executing -> {
                        binding.progress.visibility = View.VISIBLE
                        binding.reload.visibility = View.GONE
                    }
                    is TaskStatus.Finished -> binding.progress.visibility = View.GONE
                    is TaskStatus.Error -> {
                        binding.progress.visibility = View.GONE
                        binding.reload.visibility = View.VISIBLE
                    }
                    else -> Unit
                }
            }
            resultObserver = rObs
            statusObserver = sObs
            task.result.observe(lifecycleOwner, rObs)
            task.status.observe(lifecycleOwner, sObs)
        }

        fun clearObservers() {
            val task = currentTask ?: return
            resultObserver?.let { task.result.removeObserver(it) }
            statusObserver?.let { task.status.removeObserver(it) }
            resultObserver = null
            statusObserver = null
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ComicReaderV3ViewModel.ComicPage>() {
            override fun areItemsTheSame(a: ComicReaderV3ViewModel.ComicPage, b: ComicReaderV3ViewModel.ComicPage) = a.index == b.index
            override fun areContentsTheSame(a: ComicReaderV3ViewModel.ComicPage, b: ComicReaderV3ViewModel.ComicPage) = a == b
        }
    }
}
