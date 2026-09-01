package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData

interface RefreshOwner {

    val refreshState: LiveData<RefreshState>

    fun refresh(hint: RefreshHint)
}
