package ceui.pixiv.ui.pinned

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import ceui.lisa.R
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu

/**
 * 「侧边栏 → 我置顶的内容」入口页。
 *
 * 两个 tab：标签（[PinnedTagsFragment]，search_table.pinned）/ 作者（[PinnedUsersFragment]，
 * general_table 的 PINNED_USER）。两份数据本来就各存各的，页面上也分开摆 —— 以前想钉一个
 * 画师只能「搜作者名再置顶那条搜索」，钉出来的东西和标签挤在同一排里互相抢位置。
 *
 * 结构照 [ceui.pixiv.ui.watchlater.WatchLaterTabsFragment]：toolbar / 标题 / 「更多」菜单
 * （清空当前 tab）都归本页，两个子页只负责列表本体。
 */
class PinnedTabsFragment : Fragment(R.layout.viewpager_with_tablayout) {

    private val binding by viewBinding(ViewpagerWithTablayoutBinding::bind)

    private val tabTitles = listOf(R.string.v3_label_tags, R.string.string_432)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.pinned_content)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.toolbar.inflateMenu(R.menu.pinned_content)
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
                return if (position == 0) PinnedTagsFragment() else PinnedUsersFragment()
            }

            override fun getCount(): Int = tabTitles.size

            override fun getPageTitle(position: Int): CharSequence = getString(tabTitles[position])
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }

    private fun showActionMenu() {
        val position = binding.viewPager.currentItem
        showV3Menu("PinnedTabsMenu") {
            item(getString(R.string.clear), R.drawable.ic_not_interested_black_24dp) {
                clearCurrentTab(position)
            }
        }
    }

    /** 按 FragmentPagerAdapter 的 tag 取活着的子 tab（旋转后也准）。同 WatchLaterTabsFragment。 */
    private fun childTabAt(position: Int): Fragment? {
        return childFragmentManager.findFragmentByTag("android:switcher:${binding.viewPager.id}:$position")
    }

    /**
     * 清空转给子页自己做：删哪张表、删完怎么刷新，都只有持有数据源的那一侧知道。
     * 空列表直接一句提示就走 —— 不该对一个本来就空的东西走二次确认再报「已清空」。
     */
    private fun clearCurrentTab(position: Int) {
        if (position == 0) {
            val tab = childTabAt(0) as? PinnedTagsFragment
            if (tab?.hasItems() != true) {
                Common.showToast(R.string.pinned_empty)
            } else {
                tab.showClearAllDialog()
            }
        } else {
            val tab = childTabAt(1) as? PinnedUsersFragment
            if (tab?.hasItems() != true) {
                Common.showToast(R.string.pinned_empty)
            } else {
                tab.showClearAllDialog()
            }
        }
    }
}
