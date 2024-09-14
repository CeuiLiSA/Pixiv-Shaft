package ceui.pixiv.ui.circles

import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TitledViewPagerFragment

data class PagedFragmentItem(
    val builder: () -> PixivFragment,
    val initialTitle: String,
    val id: Long? = null
)


class SmartFragmentPagerAdapter(
    private var fragmentItems: List<PagedFragmentItem>,
    private val containerFragment: TitledViewPagerFragment
) : FragmentStateAdapter(
    containerFragment.childFragmentManager,
    containerFragment.viewLifecycleOwner.lifecycle
) {

    init {
        fragmentItems.forEachIndexed { index, item ->
            containerFragment.getTitleLiveData(index).value = item.initialTitle
        }
    }

    fun getPageTitle(position: Int): LiveData<String> {
        return containerFragment.getTitleLiveData(position)
    }

    override fun getItemCount(): Int {
        return fragmentItems.size
    }

    override fun createFragment(position: Int): Fragment {
        return fragmentItems[position].builder()
    }
}