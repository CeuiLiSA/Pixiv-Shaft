package ceui.lisa.fragments

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.loxia.ObjectPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * VM for the "new" illust detail page (FragmentIllust).
 *
 * Exists primarily to move the download-state probe (SAF existence + Room query)
 * off the main thread — on Android 11+ SAF queries per page add up fast, and for
 * multi-P works this was ANR'ing the detail screen on entry (issue #835).
 */
class FragmentIllustViewModel(private val illustId: Long) : ViewModel() {

    private val _hasDownload = MutableLiveData<Boolean>()
    val hasDownload: LiveData<Boolean> = _hasDownload

    /** Kick off an async download-state refresh. Result lands on [hasDownload]. */
    fun refreshDownloadState(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val illust = ObjectPool.get<IllustsBean>(illustId).value
                    ?: return@launch
                val hasLocalFile = Common.isIllustDownloaded(illust)
                val hasRecord = if (hasLocalFile) false else AppDatabase
                    .getAppDatabase(appContext)
                    .downloadDao()
                    .hasDownloadRecordByIllustId(illust.id.toLong())
                _hasDownload.postValue(hasLocalFile || hasRecord)
            } catch (e: Exception) {
                Timber.w(e, "refreshDownloadState failed illustId=%d", illustId)
            }
        }
    }

    class Factory(private val illustId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FragmentIllustViewModel(illustId) as T
        }
    }
}
