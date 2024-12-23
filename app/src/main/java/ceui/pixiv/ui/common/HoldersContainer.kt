package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData

interface HoldersContainer {
    val holders: LiveData<List<ListItemHolder>>
}