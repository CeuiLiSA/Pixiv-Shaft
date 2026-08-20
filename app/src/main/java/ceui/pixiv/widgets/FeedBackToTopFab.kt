package ceui.pixiv.widgets

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.utils.ppppx
import ceui.pixiv.witstudio.theme.V3Palette
import com.google.android.material.appbar.AppBarLayout

/**
 * 列表右下角「回顶」悬浮钮(issue #1040),设置里默认关。
 *
 * 由**宿主 Activity** 装,不由列表页自己装:同一个 [FeedFragment] 会在不同宿主里复用
 * (UserIllustFeedFragment 既在画师主页也被 TemplateActivity 独立拉起,LikeIllustFeedFragment
 * 既是画师主页的收藏 tab 也是「我的收藏」整页),而「哪些页面有回顶钮」是页面级决定。宿主在建
 * fragment 之前调 [installForHost],之后本 Activity 里每个 [FeedFragment] 的列表(含嵌套在
 * childFragmentManager 里的)建好视图时就自动挂上一个,列表页自身零改动。
 *
 * 行为对齐 V3 详情页的悬浮胶囊:下滑收起、上滑放出;点击仅回顶,长按回顶并刷新
 * ([FeedFragment.scrollToTop] / [FeedFragment.forceRefresh])。回顶同时把宿主的折叠头
 * ([AppBarLayout])展开——用户要的「回顶」是回到页面顶端,不只是把列表拨回第一条:
 * smoothScrollToPosition 到位就停,不会再产生让 AppBar 展开的 unconsumed 滚动量。
 */
object FeedBackToTopFab {

    private const val ANIMATION_DURATION_MS = 200L
    private const val SCROLL_THRESHOLD_PX = 8

    /**
     * 给 [activity] 里之后建视图的每个 [FeedFragment] 列表挂回顶钮。开关关着时什么都不注册,
     * 零开销;开关切换后已开着的页面要重进才生效(与其它设置项一致)。
     *
     * @param appBar 宿主的折叠头,回顶时一并展开;没有就传 null。
     */
    @JvmStatic
    fun installForHost(activity: FragmentActivity, appBar: AppBarLayout?) {
        if (!Shaft.sSettings.isFeedBackToTopFab) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    if (f is FeedFragment) attach(f, v, appBar)
                }
            },
            true,
        )
    }

    private fun attach(fragment: FeedFragment, view: View, appBar: AppBarLayout?) {
        // fragment_feed 的根是 FrameLayout(id feed_root),自定义骨架的页面也必须 include 它
        val root = view.findViewById<FrameLayout>(ceui.pixiv.feeds.R.id.feed_root) ?: return
        val listView = root.findViewById<RecyclerView>(ceui.pixiv.feeds.R.id.feed_list_view) ?: return
        // 横向货架(rail)没有「顶」可回,跳过;重入(rebuildList 不会重走 onFragmentViewCreated,
        // 但宿主若被重复 install 会)也跳过
        if (listView.layoutManager?.canScrollVertically() == false) return
        if (root.findViewById<View>(R.id.feed_back_to_top_fab) != null) return

        val fab = LayoutInflater.from(root.context)
            .inflate(R.layout.view_feed_back_to_top_fab, root, false) as ImageView
        val palette = V3Palette.from(root.context)
        fab.background = palette.floatingPillBg(999f * root.resources.displayMetrics.density)
        fab.imageTintList = ColorStateList.valueOf(palette.floatingPillContent)
        root.addView(fab)

        // 底距 = 导航栏 inset + 16dp:宿主全是 EdgeToEdge,列表铺到屏幕底,不补会压在导航栏上
        // (同 V3FabBarController.attachBottomInsetMargin 的做法)。
        //
        // 再加折叠头的总滚动量:宿主的列表容器(pager)挂着 appbar_scrolling_view_behavior,它按
        // 「折叠头完全收起」时的高度量尺寸,折叠头还展开着时容器底边在屏幕外,超出量 =
        // totalScrollRange + verticalOffset(展开 = 整个 range,收齐 = 0)。钉在容器底的按钮要在
        // 屏幕上纹丝不动,底距得多留一个 range,再用 translationY 抵消折叠头当前的展开量——
        // 所以收起/放出动画走 scale+alpha,不碰 translationY。
        val baseBottomMargin = 16.ppppx
        var insetBottom = 0
        var scrollRange = 0
        fun applyBottomMargin() {
            val lp = fab.layoutParams as FrameLayout.LayoutParams
            val bottom = insetBottom + baseBottomMargin + scrollRange
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom
                fab.layoutParams = lp
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(fab) { _, windowInsets ->
            insetBottom = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            applyBottomMargin()
            windowInsets
        }
        ViewCompat.requestApplyInsets(fab)
        if (appBar != null) {
            val onOffset = AppBarLayout.OnOffsetChangedListener { bar, verticalOffset ->
                scrollRange = bar.totalScrollRange
                applyBottomMargin()
                fab.translationY = -verticalOffset.toFloat()
            }
            // addOnOffsetChangedListener 不会立刻回放当前值,先按现状摆一次(折叠头已经布局过的话);
            // 还没布局过则 range 为 0,等它首次 onLayout 派发偏移再补
            val behavior = (appBar.layoutParams as? CoordinatorLayout.LayoutParams)
                ?.behavior as? AppBarLayout.Behavior
            onOffset.onOffsetChanged(appBar, behavior?.topAndBottomOffset ?: 0)
            appBar.addOnOffsetChangedListener(onOffset)
            // 监听挂在 Activity 级的 AppBar 上,列表 view 销毁(收藏 tab 换段、pager 回收)时必须摘掉
            fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    appBar.removeOnOffsetChangedListener(onOffset)
                }
            })
        }

        val expandHeader = { appBar?.setExpanded(true, true) }
        fab.setOnClickListener {
            fragment.scrollToTop()
            expandHeader()
        }
        fab.setOnLongClickListener {
            fragment.forceRefresh()
            expandHeader()
            true
        }

        var shown = true
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > SCROLL_THRESHOLD_PX && shown) {
                    shown = false
                    fab.animate().cancel()
                    fab.animate()
                        .scaleX(0f).scaleY(0f).alpha(0f)
                        .setDuration(ANIMATION_DURATION_MS)
                        .withEndAction { if (!shown) fab.visibility = View.INVISIBLE }
                        .start()
                } else if (dy < -SCROLL_THRESHOLD_PX && !shown) {
                    shown = true
                    fab.animate().cancel()
                    fab.visibility = View.VISIBLE
                    fab.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(ANIMATION_DURATION_MS)
                        .start()
                }
            }
        })
    }
}
