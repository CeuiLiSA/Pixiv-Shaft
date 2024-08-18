package ceui.pixiv.ui.home

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewPagerViewModel : ViewModel() {

    val selectedTabIndex = MutableLiveData(0)

}