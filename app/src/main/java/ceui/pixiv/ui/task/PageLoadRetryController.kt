package ceui.pixiv.ui.task

import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import ceui.lisa.R
import ceui.pixiv.utils.NetworkStateManager

/**
 * Renders the (loaded, total, failed) summary into the shared banner layout
 * defined by `view_image_load_status_banner.xml`. Hidden when there is nothing
 * to retry.
 */
fun renderImageLoadStatusBanner(
    rowView: View,
    summaryText: TextView,
    loaded: Int,
    total: Int,
    failed: Int,
) {
    if (total <= 0 || failed == 0) {
        rowView.visibility = View.GONE
        return
    }
    rowView.visibility = View.VISIBLE
    summaryText.text = summaryText.context.getString(
        R.string.image_load_status_summary, loaded, total, failed
    )
}

/**
 * Tracks per-page image load status across network transitions.
 * Auto-retries every failed page when the network comes back online,
 * and emits a (loaded, total, failed) summary for the host to render.
 */
class PageLoadRetryController(
    lifecycleOwner: LifecycleOwner,
    networkStateManager: NetworkStateManager,
    private val totalPages: () -> Int,
    private val onSummaryChanged: (loaded: Int, total: Int, failed: Int) -> Unit,
    private val onRetryAt: (Int) -> Unit,
) {

    private val statuses = mutableMapOf<Int, TaskStatus>()
    private var loadedCount = 0
    private var failedCount = 0
    private var lastEmitted: Triple<Int, Int, Int>? = null
    private var lastNetworkType: NetworkStateManager.NetworkType? = null

    init {
        networkStateManager.networkState.observe(lifecycleOwner) { type ->
            val previous = lastNetworkType
            lastNetworkType = type
            // Only react to a NONE -> online transition. Skip the very first
            // sticky emission (previous == null) so we don't fight the initial load.
            if (previous == NetworkStateManager.NetworkType.NONE && type.isOnline) {
                retryAllFailed()
            }
        }
    }

    fun reportStatus(index: Int, status: TaskStatus) {
        val previous = statuses.put(index, status)
        if (previous is TaskStatus.Finished) loadedCount--
        else if (previous is TaskStatus.Error) failedCount--
        if (status is TaskStatus.Finished) loadedCount++
        else if (status is TaskStatus.Error) failedCount++
        emitSummary()
    }

    fun retryAllFailed() {
        if (failedCount == 0) return
        val failedIndices = statuses.entries
            .filter { it.value is TaskStatus.Error }
            .map { it.key }
        for (idx in failedIndices) {
            statuses.remove(idx)
            failedCount--
            onRetryAt(idx)
        }
        emitSummary()
    }

    /** Re-emits the summary; useful when [totalPages] becomes known after init. */
    fun refresh() = emitSummary()

    private fun emitSummary() {
        val next = Triple(loadedCount, totalPages(), failedCount)
        if (next == lastEmitted) return
        lastEmitted = next
        onSummaryChanged(next.first, next.second, next.third)
    }
}
