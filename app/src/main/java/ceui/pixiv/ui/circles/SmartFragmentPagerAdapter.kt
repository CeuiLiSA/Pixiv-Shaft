package ceui.pixiv.ui.circles

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.pixiv.ui.common.PixivFragment

data class PagedFragmentItem(val builder: ()-> PixivFragment, val title: String, val id: Long? = null)


class SmartFragmentPagerAdapter(
    private var fragmentItems: List<PagedFragmentItem>,
    containerFragment: Fragment
): FragmentStateAdapter(containerFragment.childFragmentManager, containerFragment.viewLifecycleOwner.lifecycle) {

    fun getPageTitle(position: Int): CharSequence {
        return fragmentItems[position].title
    }

    override fun getItemCount(): Int {
        return fragmentItems.size
    }

    override fun createFragment(position: Int): Fragment {
        return fragmentItems[position].builder()
    }
}