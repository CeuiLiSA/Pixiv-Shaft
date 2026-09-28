package ceui.pixiv.ui.comic.reader

import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Bridge：把"阅读模式（横翻 / 条漫）"作为抽象，把"具体的容器实现（ViewPager2 / RecyclerView）"
 * 作为实现独立。Fragment 仅持有 [ComicViewport] 抽象引用，模式切换通过容器替换完成。
 *
 * （之前命名 Strategy 实为 Bridge——因为两种实现差的不是"算法"而是"容器机制"。）
 */
sealed interface ComicViewport {

    fun activate(pages: List<ComicReaderV3ViewModel.ComicPage>, resumeIndex: Int)
    fun deactivate()

    /** [animated] 只给逐页翻（点击区 / 音量键）用；跳页（进度条 / 书签 / 页面预览）一律瞬移。 */
    fun jumpTo(index: Int, animated: Boolean = false)
    fun currentIndex(): Int
}

class PagedViewport(
    private val pager: ViewPager2,
    private val adapter: ComicPagerAdapter,
    private val onPageSelected: (Int) -> Unit,
) : ComicViewport {

    init {
        adapter.fillHeight = true
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { this@PagedViewport.onPageSelected(position) }
        })
    }

    fun applyDirection() {
        pager.layoutDirection = if (ComicReaderSettings.pageDirection == ComicReaderSettings.PageDirection.RTL)
            View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    fun applyTransformer() {
        pager.setPageTransformer(when (ComicReaderSettings.flipAnim) {
            ComicReaderSettings.FlipAnim.Slide -> ComicPageTransformers.Slide
            ComicReaderSettings.FlipAnim.Cover -> ComicPageTransformers.Cover
            ComicReaderSettings.FlipAnim.Depth -> ComicPageTransformers.Depth
            ComicReaderSettings.FlipAnim.FlipBook -> ComicPageTransformers.FlipBook
        })
    }

    fun applyOffscreenLimit() {
        pager.offscreenPageLimit = ComicReaderSettings.preloadAhead.coerceAtLeast(1)
    }

    override fun activate(pages: List<ComicReaderV3ViewModel.ComicPage>, resumeIndex: Int) {
        pager.isVisible = true
        adapter.submitList(pages) {
            pager.setCurrentItem(resumeIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)), false)
        }
    }

    override fun deactivate() { pager.isVisible = false }
    // 平滑滚动会走当前的 page transformer，点击翻页和手指滑动是同一套动画(#1172)。
    override fun jumpTo(index: Int, animated: Boolean) { pager.setCurrentItem(index, animated) }
    override fun currentIndex(): Int = pager.currentItem
}

class WebtoonViewport(
    private val recyclerView: RecyclerView,
    private val adapter: ComicPagerAdapter,
    private val onPageVisible: (Int) -> Unit,
) : ComicViewport {

    private val layoutManager = LinearLayoutManager(recyclerView.context)

    init {
        adapter.fillHeight = false
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val pos = layoutManager.findFirstVisibleItemPosition()
                if (pos >= 0) onPageVisible(pos)
            }
        })
    }

    override fun activate(pages: List<ComicReaderV3ViewModel.ComicPage>, resumeIndex: Int) {
        recyclerView.isVisible = true
        adapter.submitList(pages) {
            layoutManager.scrollToPositionWithOffset(
                resumeIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)), 0
            )
        }
    }

    override fun deactivate() { recyclerView.isVisible = false }
    // 条漫点击不翻页，只有音量键会逐页走到这里；整屏长图平滑滚不是「翻页」，照旧直接对齐。
    override fun jumpTo(index: Int, animated: Boolean) { layoutManager.scrollToPositionWithOffset(index, 0) }
    override fun currentIndex(): Int = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
}
