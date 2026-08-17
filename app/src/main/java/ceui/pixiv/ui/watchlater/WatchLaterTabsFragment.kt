package ceui.pixiv.ui.watchlater

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import ceui.lisa.R
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.lisa.utils.Common
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.slideshow.SlideshowLauncher
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import java.util.ArrayList

/**
 * 「稍后再看」入口页：对齐浏览记录页的 tab 结构，复用同一个侧边栏入口（不新增入口）。
 *
 * 两个 tab：插画作品（[WatchLaterFeedFragment]）/ 小说作品（[NovelWatchLaterFeedFragment]），
 * 共读 general_table 的 WATCH_LATER 记录，按 entityType 分开渲染。
 * toolbar 的「更多」菜单按当前 tab 给动作：插画 tab = 播放全部 + 清空，小说 tab = 清空。
 */
class WatchLaterTabsFragment : Fragment(R.layout.viewpager_with_tablayout) {

    private val binding by viewBinding(ViewpagerWithTablayoutBinding::bind)

    private val tabTitles = listOf(R.string.string_246, R.string.string_237)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.watch_later)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.toolbar.inflateMenu(R.menu.watch_later)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_more) {
                showActionMenu()
                true
            } else {
                false
            }
        }

        binding.viewPager.adapter = object : FragmentPagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment {
                return if (position == 0) WatchLaterFeedFragment() else NovelWatchLaterFeedFragment()
            }

            override fun getCount(): Int = tabTitles.size

            override fun getPageTitle(position: Int): CharSequence = getString(tabTitles[position])
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }

    private fun showActionMenu() {
        val position = binding.viewPager.currentItem
        showV3Menu("WatchLaterTabsMenu") {
            if (position == 0) {
                item(getString(R.string.watch_later_play_all), R.drawable.ic_baseline_play_arrow_24) {
                    playAll()
                }
            }
            item(getString(R.string.watch_later_clear), R.drawable.ic_not_interested_black_24dp) {
                confirmClear(position)
            }
        }
    }

    /** 按 FragmentPagerAdapter 的 tag 取活着的子 tab（旋转后也准）。同 FragmentHistoryTabs 的取法。 */
    private fun childTabAt(position: Int): Fragment? {
        return childFragmentManager.findFragmentByTag("android:switcher:${binding.viewPager.id}:$position")
    }

    /** 当前 tab 的列表是不是空的（子页还没建出来也算空）。 */
    private fun isTabEmpty(position: Int): Boolean {
        return if (position == 0) {
            (childTabAt(0) as? WatchLaterFeedFragment)?.currentBeans().isNullOrEmpty()
        } else {
            (childTabAt(1) as? NovelWatchLaterFeedFragment)?.currentNovelItems().isNullOrEmpty()
        }
    }

    private fun playAll() {
        val beans = (childTabAt(0) as? WatchLaterFeedFragment)?.currentBeans().orEmpty()
        if (beans.isEmpty()) {
            Common.showToast(R.string.watch_later_empty)
        } else {
            SlideshowLauncher.launchFromIllustsBeans(requireContext(), ArrayList(beans), 0, true)
        }
    }

    private fun confirmClear(position: Int) {
        val ctx = context ?: return
        // 空列表不该走二次确认再报一句「已清空」——那是在清空一个本来就空的东西。
        if (isTabEmpty(position)) {
            Common.showToast(R.string.watch_later_empty)
            return
        }
        // EntityWrapper 是 app 单例；提前抓好，弹窗动作异步触发时 fragment 可能已 detach。
        val entityWrapper = requireEntityWrapper()
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.watch_later)
            .setMessage(R.string.watch_later_clear_confirm)
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.watch_later_clear_ok, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                // 清空会发 WATCH_LATER_CHANGED 广播触发子 tab refresh，不用手动清列表。
                if (position == 0) {
                    entityWrapper.clearIllustWatchLater(ctx.applicationContext)
                } else {
                    entityWrapper.clearNovelWatchLater(ctx.applicationContext)
                }
                Common.showToast(R.string.watch_later_cleared)
            }
            .show()
    }
}
