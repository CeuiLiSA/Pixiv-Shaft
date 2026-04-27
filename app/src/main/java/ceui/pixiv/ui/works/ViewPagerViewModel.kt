package ceui.pixiv.ui.works

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.utils.Common
import ceui.loxia.Event

class ViewPagerViewModel : ViewModel() {

    private val _downloadEvent = MutableLiveData<Event<Int>>()

    val downloadEvent: LiveData<Event<Int>> = _downloadEvent

    // PagedImgUrlFragment 持有的 illust id；ImgDisplayFragment 在保存图片时
    // 用它从 ObjectPool 取回 Illust 写入下载历史。0 表示当前 pager 不是插画详情。
    var illustId: Long = 0L

    fun triggerDownloadEvent(index: Int) {
        _downloadEvent.postValue(Event(index))
    }
}
