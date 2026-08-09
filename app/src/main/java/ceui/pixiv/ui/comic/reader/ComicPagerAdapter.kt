package ceui.pixiv.ui.comic.reader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.databinding.CellComicPageBinding
import ceui.pixiv.imageloader.Disposable
import ceui.pixiv.imageloader.ImageLoadState
import ceui.pixiv.imageloader.ImageLoaderV3
import ceui.pixiv.imageloader.observeState
import ceui.pixiv.ui.task.TaskStatus
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.view.zoom.OnViewLongPressListener
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import com.github.panpf.zoomimage.zoom.ContentScaleCompat
import timber.log.Timber

/**
 * 漫画页面适配器：依赖倒置后只接 [LifecycleOwner] 与函数引用，不再引用 Fragment / Settings。
 *
 * 职责单一：把 [ComicReaderV3ViewModel.ComicPage] 渲染到 [com.github.panpf.zoomimage.SketchZoomImageView]。
 * 加载链路：URL 由 [urlResolver] 决定 → V3 imageloader(进程级共享任务/去重/跨导航保留) → image.loadImage(file)。
 *
 * 业务规则（preview vs original / fitMode 映射）由调用方注入，符合单向依赖。
 */
class ComicPagerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val urlResolver: (ComicReaderV3ViewModel.ComicPage) -> String,
    private val contentScaleProvider: () -> ContentScaleCompat,
    private val onSingleTap: (TapZone) -> Unit,
    private val onLongPressPage: ((Int) -> Unit)? = null,
    private val onPageStatusChanged: ((Int, TaskStatus) -> Unit)? = null,
    // 下载进度环颜色随阅读器黑/白底变化,由调用方注入(依赖倒置,不引用 Settings)。
    private val indicatorColorProvider: () -> Int = { Color.WHITE },
) : ListAdapter<ComicReaderV3ViewModel.ComicPage, ComicPagerAdapter.PageHolder>(DIFF) {

    enum class TapZone { Left, Center, Right }

    var fillHeight: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val binding = CellComicPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.image.layoutParams = binding.image.layoutParams.apply {
            height = if (fillHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        return PageHolder(lifecycleOwner, binding, urlResolver, contentScaleProvider, onSingleTap, onLongPressPage, onPageStatusChanged, indicatorColorProvider)
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
        private val onPageStatusChanged: ((Int, TaskStatus) -> Unit)?,
        private val indicatorColorProvider: () -> Int,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var imageDisposable: Disposable? = null

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
                // 重绑：bind 内对 Error 终态任务会 retry()（对齐旧 TaskPool removeTask 后重下）。
                bind(current)
            }
        }

        fun bind(page: ComicReaderV3ViewModel.ComicPage) {
            binding.root.tag = page
            binding.image.zoomable.setContentScale(contentScaleProvider())

            clearObservers()
            binding.reload.visibility = View.GONE
            // 进度环着色 + 归零(确定模式,复用时避免残留上一页的百分比)。
            val indicatorColor = indicatorColorProvider()
            binding.progress.setIndicatorColor(indicatorColor)
            binding.progress.trackColor = ColorUtils.setAlphaComponent(indicatorColor, 0x33)
            binding.progress.setProgressCompat(0, false)
            binding.progress.visibility = View.VISIBLE

            val url = urlResolver(page)
            val task = ImageLoaderV3.obtain(url)
            // 上一次失败的任务在(重新)绑定时重来一次，对齐旧 TaskPool「取到 errored 即重下」。
            if (task.state.value is ImageLoadState.Error) task.retry()
            // 已下好则秒显(共享/缓存命中)。
            task.currentFile?.let { binding.image.loadImage(it) }

            imageDisposable = task.observeState(lifecycleOwner) { state ->
                when (state) {
                    is ImageLoadState.Loading -> {
                        binding.progress.visibility = View.VISIBLE
                        binding.progress.setProgressCompat(state.percent, true)
                        binding.reload.visibility = View.GONE
                        onPageStatusChanged?.invoke(page.index, TaskStatus.Executing(state.percent))
                    }
                    is ImageLoadState.Success -> {
                        Timber.d("[ComicPager] page=${page.index} file ready size=${state.file.length()}")
                        binding.image.loadImage(state.file)
                        binding.progress.visibility = View.GONE
                        binding.reload.visibility = View.GONE
                        onPageStatusChanged?.invoke(page.index, TaskStatus.Finished)
                    }
                    is ImageLoadState.Error -> {
                        binding.progress.visibility = View.GONE
                        binding.reload.visibility = View.VISIBLE
                        val cause = state.cause
                        onPageStatusChanged?.invoke(
                            page.index,
                            TaskStatus.Error(if (cause is Exception) cause else Exception(cause)),
                        )
                    }
                    ImageLoadState.Idle -> Unit
                }
            }
        }

        fun clearObservers() {
            imageDisposable?.dispose()
            imageDisposable = null
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ComicReaderV3ViewModel.ComicPage>() {
            override fun areItemsTheSame(a: ComicReaderV3ViewModel.ComicPage, b: ComicReaderV3ViewModel.ComicPage) = a.index == b.index
            override fun areContentsTheSame(a: ComicReaderV3ViewModel.ComicPage, b: ComicReaderV3ViewModel.ComicPage) = a == b
        }
    }
}
