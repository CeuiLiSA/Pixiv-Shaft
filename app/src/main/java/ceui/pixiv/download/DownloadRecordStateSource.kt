package ceui.pixiv.download

import ceui.pixiv.communication.StateEntry
import ceui.pixiv.communication.StateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Rebuilds the legacy "downloaded before" flag from records/files, never from queue removal.
 * Invalidations must replay an initial tick and conflate changes while the probe is running.
 * Each new collection probes again, including after a hidden/destroyed detail view returns.
 */
internal class DownloadRecordStateSource(
    private val invalidations: (Long) -> Flow<Unit>,
    private val probe: suspend (Long) -> Boolean,
) : StateSource<Long, Boolean> {
    override fun observe(key: Long): Flow<StateEntry<Boolean>> = flow {
        Timber.tag(LOG_TAG).d("subscribe illustId=%d", key)
        emit(StateEntry.Unknown)
        try {
            invalidations(key).collect {
                Timber.tag(LOG_TAG).d("check illustId=%d reason=initial_or_records_changed", key)
                val state = try {
                    StateEntry.Value(probe(key)).also {
                        Timber.tag(LOG_TAG).d("checked illustId=%d downloaded=%s", key, it.value)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Keep listening: a later change/resubscription can recover from a failed read.
                    Timber.tag(LOG_TAG).w(error, "check failed illustId=%d", key)
                    StateEntry.Unknown
                }
                emit(state)
            }
        } finally {
            Timber.tag(LOG_TAG).d("unsubscribe illustId=%d", key)
        }
    }.distinctUntilChanged()

    companion object {
        const val LOG_TAG = "IllustDownloadState"

        /** Filter table-wide ticks with a cheap indexed lookup before any legacy/file probe. */
        fun recordChanges(
            key: Long,
            changes: Flow<Unit>,
            hasIndexedRecord: suspend (Long) -> Boolean,
        ): Flow<Unit> = changes.map {
            try {
                StateEntry.Value(hasIndexedRecord(key))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag(LOG_TAG).w(error, "indexed check failed illustId=%d", key)
                // A recovered lookup must emit even if the Boolean matches the pre-error value.
                StateEntry.Unknown
            }
        }.distinctUntilChanged().map { Unit }
    }
}
