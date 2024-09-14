package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState

interface RefreshOwner {

    val refreshState: LiveData<RefreshState>

    fun refresh(hint: RefreshHint)
}
