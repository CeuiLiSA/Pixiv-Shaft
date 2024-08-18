package ceui.pixiv.widgets

import android.view.View
import androidx.databinding.BindingAdapter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.TitleTabViewBinding
import ceui.lisa.utils.Common
import ceui.pixiv.ui.circles.SmartFragmentPagerAdapter
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import kotlin.math.abs
import kotlin.math.max


@BindingAdapter("tabFocusFactor")
fun View.binding_setTabFocusFacator(focusFactor: Float) {
    this.scaleX = (focusFactor * 0.2F) + 0.8F
    this.scaleY = (focusFactor * 0.2F) + 0.8F
    this.alpha = (focusFactor * 0.3F) + 0.7F
}

fun View.setTabFocusFactor(factor: Float) {
    scaleX = 1.0f + factor * 0.2f
    scaleY = 1.0f + factor * 0.2f
}

fun View.setTabFocusFactorBigger(factor: Float) {
    scaleX = 1.0f + factor * 0.3f
    scaleY = 1.0f + factor * 0.3f
}

class TitleTabHolder(val title: String) : ListItemHolder() {
    val focusFactor = MutableLiveData(1F)
    val showsRedDot = MutableLiveData(false)
}


@ItemHolder(TitleTabHolder::class)
class TitleTabViewHolder(bd: TitleTabViewBinding) : ListItemViewHolder<TitleTabViewBinding, TitleTabHolder>(bd){

    override fun onBindViewHolder(holder: TitleTabHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.viewModel = holder
    }
}


fun List<TitleTabHolder>.updateTabFocus(focus: Float) {
    forEachIndexed { index, tab ->
        tab.focusFactor.value = max(0F, 1F - abs(focus - index))
    }
}

fun RecyclerView.setUpWith(viewPager: ViewPager2, slidingCursorView: SlidingCursorView, viewLifecycleOwner: LifecycleOwner, scrollToTopAction: ()->Unit) {
    val tabs = (0 until viewPager.adapter!!.itemCount).map {
        val title = (viewPager.adapter as SmartFragmentPagerAdapter).getPageTitle(it).toString()
        TitleTabHolder(title)
    }

    adapter = CommonAdapter(viewLifecycleOwner).apply {
        submitList(tabs)
    }

    slidingCursorView.addFocusIndexChangeListener { focus, pos ->
        tabs.updateTabFocus(focus)
        smoothScrollToPosition(pos)
    }

    slidingCursorView.getLeftAndWidthForPosition = { pos ->
        val itemView = findViewHolderForAdapterPosition(pos)?.itemView
        if (itemView == null) {
            Common.showLog("sadsad2 aaa leftAndWidthBlock")
            Pair(0, 0)
        } else {
            val ret = Pair(itemView.left, itemView.width)
            Common.showLog("sadsad2 bbb leftAndWidthBlock ${ret}")
            ret
        }
    }

    slidingCursorView.focusIndex = viewPager.currentItem.toFloat()

    viewPager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            super.onPageScrolled(position, positionOffset, positionOffsetPixels)
            // positionOffset is from 0 to 1, with 0.5 being totalled selected
            slidingCursorView.focusIndex = (position + positionOffset)
        }
    })
}

fun RecyclerView.getTitleTabAt(pos: Int): TitleTabHolder? {
    return (adapter as? CommonAdapter)?.currentList?.get(pos) as? TitleTabHolder
}