package ceui.pixiv.ui.navigation

import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.behavior.HideViewOnScrollBehavior
import java.util.WeakHashMap

/**
 * 首页底栏「下滑收起 / 上滑恢复」。
 *
 * 动画本身用官方的 [HideViewOnScrollBehavior]（挂在 activity_cover.xml 的底栏上），但**触发**
 * 不能交给 CoordinatorLayout 的嵌套滚动分发：五个 tab 里有三个（推荐 / 动态 / R18）自己就套了
 * 一层 CoordinatorLayout（AppBarLayout、FragmentRight 的两个自定义 behavior），嵌套滚动在内层
 * 就被吃掉——androidx 的 CoordinatorLayout 只实现 NestedScrollingParent3、并不是
 * NestedScrollingChild，内外两层天然不串联，事件永远到不了外层底栏。纯 XML 挂 behavior 的写法
 * 只在「发现 / 我」两个 tab 上生效，另外三个纹丝不动。
 *
 * 所以这里主动喂：给每个 fragment 视图里的竖向滚动容器挂监听，把滚动方向翻译成 behavior 的
 * [HideViewOnScrollBehavior.slideIn] / [HideViewOnScrollBehavior.slideOut]。监听按 fragment
 * 视图创建时机安装（[FragmentManager.registerFragmentLifecycleCallbacks] 带递归，子
 * FragmentManager 里的列表也覆盖到），以后加 tab 不需要再来这里登记。
 */
class BottomBarAutoHide(private val bar: View) {

    /** 抖动阈值：手指刚落下的一两像素不该把底栏抽走。 */
    private val slop = ViewConfiguration.get(bar.context).scaledTouchSlop

    /** 同方向累计位移，越过 [slop] 才动，方向一变立即清零。 */
    private var accumulated = 0

    /** 已挂过监听的滚动容器；ViewPager 复用页面时不重复挂。 */
    private val bound = WeakHashMap<View, Unit>()

    @Suppress("UNCHECKED_CAST")
    private val behavior: HideViewOnScrollBehavior<View>?
        get() = (bar.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior
                as? HideViewOnScrollBehavior<View>

    fun install(activity: FragmentActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    bindScrollers(v)
                }
            },
            /* recursive = */ true,
        )
    }

    /** 底栏必须重新露出的时刻（换 tab 等）：不然收起状态下切过去，新 tab 也没底栏可点。 */
    fun reveal() {
        accumulated = 0
        val behavior = behavior ?: return
        // behavior 的滑动距离是 onLayoutChild 里量出来的，没排版过就调用会拿到空的 delegate。
        if (bar.isLaidOut) {
            behavior.slideIn(bar)
        }
    }

    private fun onScrollDelta(dy: Int) {
        if (dy == 0) return
        val behavior = behavior ?: return
        if (!bar.isLaidOut) return
        if ((accumulated > 0) != (dy > 0)) {
            accumulated = 0
        }
        accumulated += dy
        if (accumulated > slop) {
            behavior.slideOut(bar)
            accumulated = 0
        } else if (accumulated < -slop) {
            behavior.slideIn(bar)
            accumulated = 0
        }
    }

    /**
     * 只认竖向位移：横向货架（发现 tab 的各条 rail、动态 tab 的推荐用户）dy 恒为 0，挂上也不会
     * 误触发，不必额外判 LayoutManager 方向。
     */
    private fun bindScrollers(view: View) {
        when (view) {
            is RecyclerView -> {
                if (bound.put(view, Unit) == null) {
                    view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                            onScrollDelta(dy)
                        }
                    })
                }
                // RecyclerView 的孩子是 item，视图创建这一刻还没有，不必往下走。
                return
            }

            is NestedScrollView -> {
                if (bound.put(view, Unit) == null) {
                    view.setOnScrollChangeListener(
                        NestedScrollView.OnScrollChangeListener { _, _, y, _, oldY ->
                            onScrollDelta(y - oldY)
                        }
                    )
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                bindScrollers(view.getChildAt(i))
            }
        }
    }
}
