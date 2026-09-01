package ceui.pixiv.ui.common

import android.content.Intent
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.view.LinearItemDecoration
import ceui.lisa.view.LinearItemDecorationNoLRTB
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Novel
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.BarUtils
import timber.log.Timber

/**
 * 旧 PixivFragment 框架清理后保留下来的共享工具方法 / 标记接口。
 * PixivFragment 及其列表子类、fragment_pixiv_list 均已删除,但这些 helper 仍被
 * feeds 框架(FeedFragment / 各 FeedFragment 子页)、PinnedTagsFragment、小说详情等存活代码
 * 复用,故独立到本文件(包名保持 ceui.pixiv.ui.common,现有 import 不变)。
 *
 * 已随框架一并删除的两个 helper:setUpRefreshState / setUpCustomAdapter —— 它们绑定
 * 已删除的 FragmentPixivListBinding,且只服务已删除的列表子类。
 */

interface ViewPagerFragment

interface HomeTabContainer : ViewPagerFragment {
    fun bottomExtraSpacing(): Int = 100.ppppx
}

/**
 * fragment_toolbar_feed 的设置页式 AppCompat Toolbar(fragment_webview 5 件套)。
 * BaseActivity 开了 EdgeToEdge:状态栏 inset 走 BarUtils 手动 padding,不用
 * fitsSystemWindows(会把 status + nav 两个 inset 都当 padding 套上);[content] 底部让出导航栏。
 */
fun Fragment.setUpToolbar(binding: FragmentToolbarFeedBinding, content: ViewGroup) {
    binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
    binding.toolbar.setNavigationOnClickListener {
        requireActivity().finish()
    }
    ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        content.updatePadding(0, 0, 0, insets.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

fun Fragment.setUpLayoutManager(listView: RecyclerView, listMode: Int = ListMode.STAGGERED_GRID) {
    val ctx = requireContext()
    listView.itemAnimator = null
    if (listMode == ListMode.STAGGERED_GRID) {
        listView.addItemDecoration(SpacesItemDecoration(4.ppppx))
        listView.layoutManager = StaggeredManager(2, StaggeredGridLayoutManager.VERTICAL)
    } else if (listMode == ListMode.VERTICAL) {
        listView.layoutManager = LinearLayoutManager(ctx)
        listView.addItemDecoration(LinearItemDecoration(18.ppppx))
    } else if (listMode == ListMode.VERTICAL_NO_HORIZONTAL) {
        listView.layoutManager = LinearLayoutManager(ctx)
        listView.addItemDecoration(LinearItemDecorationNoLRTB(18.ppppx))
    } else if (listMode == ListMode.VERTICAL_COMMENT) {
        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.addItemDecoration(LinearItemDecoration(10.ppppx))
    } else if (listMode == ListMode.VERTICAL_TABCELL) {
        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.addItemDecoration(
            BottomDividerDecoration(
                requireContext(),
                R.drawable.list_divider,
            )
        )
    } else if (listMode == ListMode.VERTICAL_NO_MARGIN) {
        listView.layoutManager = LinearLayoutManager(ctx)
    } else if (listMode == ListMode.GRID) {
        listView.layoutManager = GridLayoutManager(ctx, 3)
    }
}

fun FragmentActivity.findCurrentFragmentOrNull(): Fragment? {
    return try {
        // 无 NavHost(TemplateActivity 等裸 FragmentManager 宿主):取栈顶可见 fragment。
        val currentFragment = supportFragmentManager.fragments.lastOrNull { it.isVisible }
        currentFragment?.let {
            Timber.d("Current Fragment Instance: ${it.javaClass.simpleName}")
        }
        currentFragment
    } catch (ex: Exception) {
        Timber.e(ex)
        null
    }
}


const val NOVEL_URL_HEAD = "https://www.pixiv.net/novel/show.php?id="

fun Fragment.shareNovel(novel: Novel) {
    launchSuspend {
        val ctx = requireContext()
        val shareText = ctx.getString(
            R.string.share_illust,
            novel.title,
            novel.user?.name,
            NOVEL_URL_HEAD + novel.id
        )

        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }.also { intent ->
            startActivity(Intent.createChooser(intent, ctx.getString(R.string.share)))
        }
    }
}
