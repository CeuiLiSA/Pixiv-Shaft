package ceui.lisa.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.ui.collection.LikeNovelFeedFragment
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.ui.collection.LikeIllustFeedFragment

private const val ARG_USER_ID = "collection_user_id"
private const val STATE_SEGMENT = "collection_segment"
private const val SEG_ILLUST = 0
private const val SEG_NOVEL = 1

/**
 * 画师主页「收藏」Tab —— 顶部分段控件在「插画/漫画收藏」和「小说收藏」间切换,
 * 各自复用现有的 LikeIllustFeedFragment / FragmentLikeNovel(showToolbar=false)。
 *
 * 把旧版散在折叠头「导航」药丸里的两个收藏入口收敛进一个 Tab。
 */
class UserV3CollectionFragment : Fragment(), UserV3TopInsetConsumer {

    private var userId = 0L
    private var selected = SEG_ILLUST
    private var contentLoaded = false
    private var hostTopInsetPx = 0
    private var observedListView: RecyclerView? = null
    private var childLifecycleCallbacks: FragmentManager.FragmentLifecycleCallbacks? = null
    private lateinit var palette: V3Palette
    private lateinit var segBar: View
    private lateinit var segIllust: TextView
    private lateinit var segNovel: TextView

    private val listScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            syncSegBarTranslation(recyclerView)
        }
    }

    companion object {
        fun newInstance(userId: Long): UserV3CollectionFragment =
            UserV3CollectionFragment().apply {
                arguments = Bundle().apply { putLong(ARG_USER_ID, userId) }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        userId = Params.getLongCompat(arguments, ARG_USER_ID)
        return inflater.inflate(R.layout.fragment_user_v3_collection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        palette = V3Palette.from(requireContext())
        segBar = view.findViewById(R.id.collection_seg_bar)
        segIllust = view.findViewById(R.id.seg_illust)
        segNovel = view.findViewById(R.id.seg_novel)
        segBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if ((bottom - top) != (oldBottom - oldTop)) {
                applyTopInsets()
                observedListView?.let(::syncSegBarTranslation)
            }
        }
        installChildFeedObserver()
        segIllust.text = getString(R.string.string_164) // 插画/漫画收藏
        segNovel.text = getString(R.string.string_192)  // 小说收藏
        segIllust.setOnClickListener { select(SEG_ILLUST) }
        segNovel.setOnClickListener { select(SEG_NOVEL) }

        selected = savedInstanceState?.getInt(STATE_SEGMENT, SEG_ILLUST) ?: SEG_ILLUST
        // 旋转/重建时 childFragmentManager 会自己恢复内嵌 fragment,标记为已加载,别再 replace 丢滚动位置。
        if (savedInstanceState != null) {
            contentLoaded = childFragmentManager.findFragmentById(R.id.collection_container) != null
        }
        updateSegmentStyle()
        applyTopInsets()
    }

    override fun onDestroyView() {
        observedListView?.removeOnScrollListener(listScrollListener)
        observedListView = null
        childLifecycleCallbacks?.let { childFragmentManager.unregisterFragmentLifecycleCallbacks(it) }
        childLifecycleCallbacks = null
        super.onDestroyView()
    }

    override fun setUserTopInset(topInsetPx: Int) {
        val clamped = topInsetPx.coerceAtLeast(0)
        if (hostTopInsetPx == clamped && view == null) return
        hostTopInsetPx = clamped
        if (view != null) {
            applyTopInsets()
            observedListView?.let(::syncSegBarTranslation)
        }
    }

    private fun installChildFeedObserver() {
        val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager,
                f: Fragment,
                v: View,
                savedInstanceState: Bundle?,
            ) {
                if (f.id == R.id.collection_container) {
                    bindChildList(f, v)
                }
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                if (f.id == R.id.collection_container) {
                    observedListView?.removeOnScrollListener(listScrollListener)
                    observedListView = null
                }
            }
        }
        childLifecycleCallbacks = callbacks
        childFragmentManager.registerFragmentLifecycleCallbacks(callbacks, false)
        childFragmentManager.findFragmentById(R.id.collection_container)?.let { existing ->
            existing.view?.let { bindChildList(existing, it) }
        }
    }

    private fun bindChildList(fragment: Fragment, root: View) {
        if (fragment is FeedFragment) {
            fragment.setTopContentInset(computeListTopInset())
        }
        val rv = root.findViewById<RecyclerView?>(ceui.pixiv.feeds.R.id.feed_list_view) ?: return
        if (observedListView !== rv) {
            observedListView?.removeOnScrollListener(listScrollListener)
            observedListView = rv
            rv.addOnScrollListener(listScrollListener)
        }
        syncSegBarTranslation(rv)
    }

    private fun computeListTopInset(): Int {
        val barH = if (::segBar.isInitialized) segBar.height else 0
        return hostTopInsetPx + barH
    }

    private fun applyTopInsets() {
        if (!::segBar.isInitialized) return
        segBar.updateLayoutParams<FrameLayout.LayoutParams> {
            if (topMargin != hostTopInsetPx) {
                topMargin = hostTopInsetPx
            }
        }
        val child = childFragmentManager.findFragmentById(R.id.collection_container)
        if (child is FeedFragment) {
            child.setTopContentInset(computeListTopInset())
        }
    }

    private fun syncSegBarTranslation(rv: RecyclerView) {
        if (!::segBar.isInitialized) return
        val maxTravel = hostTopInsetPx + segBar.height + 64
        val scrolled = computeRecyclerScrolledFromTop(rv, maxTravel)
        val targetY = -scrolled.toFloat()
        if (segBar.translationY != targetY) {
            segBar.translationY = targetY
        }
    }

    // 懒加载:收藏 Tab 是插画 Tab 的相邻页,会被 ViewPager2 预取(onViewCreated 先跑)。此时不加载列表,
    // 只有真正切到收藏 Tab(fragment RESUMED,onResume 触发)才创建子 fragment 发请求 —— 进主页不打 bookmarks。
    override fun onResume() {
        super.onResume()
        if (!contentLoaded) {
            contentLoaded = true
            showSegment(selected)
        }
    }

    private fun select(seg: Int) {
        if (seg == selected &&
            childFragmentManager.findFragmentById(R.id.collection_container) != null
        ) return
        selected = seg
        showSegment(seg)
    }

    private fun showSegment(seg: Int) {
        if (::segBar.isInitialized) {
            segBar.translationY = 0f
        }
        val frag = when (seg) {
            SEG_NOVEL -> LikeNovelFeedFragment.newInstance(userId, Params.TYPE_PUBLIC, false)
            else -> LikeIllustFeedFragment.newInstance(userId, Params.TYPE_PUBLIC, false)
        }
        frag.setTopContentInset(computeListTopInset())
        childFragmentManager.beginTransaction()
            .replace(R.id.collection_container, frag)
            .commit()
        updateSegmentStyle()
    }

    private fun updateSegmentStyle() {
        applyStyle(segIllust, selected == SEG_ILLUST)
        applyStyle(segNovel, selected == SEG_NOVEL)
    }

    private fun applyStyle(tv: TextView, active: Boolean) {
        val dp = resources.displayMetrics.density
        if (active) {
            tv.background = palette.pillPrimary(999f * dp)
            tv.setTextColor(resources.getColor(R.color.v3_text_1, null))
        } else {
            tv.background = palette.pillSecondary(999f * dp, (1 * dp).toInt())
            tv.setTextColor(palette.textSecondary)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SEGMENT, selected)
    }
}
