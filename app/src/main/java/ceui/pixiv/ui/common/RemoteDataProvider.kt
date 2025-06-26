package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import ceui.loxia.Event

interface RemoteDataProvider {
    val remoteDataSyncedEvent: LiveData<Event<Long>>
}