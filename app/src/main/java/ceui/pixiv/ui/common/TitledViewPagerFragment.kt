package ceui.pixiv.ui.common

import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData


open class TitledViewPagerFragment(layoutId: Int) : PixivFragment(layoutId), ITitledViewPager {

    private val viewPagerViewModel by viewModels<CommonViewPagerViewModel>()

    override fun getTitleLiveData(index: Int): MutableLiveData<String> {
        return viewPagerViewModel.getTitleLiveData(index)
    }
}