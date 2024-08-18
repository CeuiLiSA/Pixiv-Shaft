package ceui.loxia

import androidx.lifecycle.MutableLiveData
import ceui.pixiv.ui.common.ListItemHolder

abstract class Repository<FragmentT: NavFragment> {

    val refreshState = MutableLiveData<RefreshState>()
    val holderList = MutableLiveData<List<ListItemHolder>>()

    abstract suspend fun refresh(
        fragment: FragmentT
    )

    abstract suspend fun loadMore(
        fragment: FragmentT
    )

    suspend fun refreshInvoker(
        frag: FragmentT,
        hint: RefreshHint
    ) {
        try {
            refreshState.value = RefreshState.LOADING(refreshHint = hint)
            refresh(frag)
        } catch (ex: Exception) {
            ex.printStackTrace()
            holderList.value = listOf()
            refreshState.value = RefreshState.ERROR(ex)
        }
    }

    suspend fun loadMoreInvoker(frag: FragmentT) {
        try {
            refreshState.value = RefreshState.LOADING(refreshHint = RefreshHint.LoadMore)
            loadMore(frag)
        } catch (ex: Exception) {
            ex.printStackTrace()
            holderList.value = listOf()
            refreshState.value = RefreshState.ERROR(ex)
        }
    }
}
