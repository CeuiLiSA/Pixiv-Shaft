package ceui.pixiv.nana7mi.session.internal

import ceui.pixiv.nana7mi.session.ExecutionLease
import ceui.pixiv.nana7mi.session.SerialExecutionCoordinator
import ceui.pixiv.nana7mi.session.SessionClock
import ceui.pixiv.nana7mi.session.SessionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

internal class DefaultSerialExecutionCoordinator(
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: SessionClock,
    private val observer: SafeSessionObserver,
) : SerialExecutionCoordinator {

    private val semaphore: Semaphore = Semaphore(permits = 1)

    override suspend fun <T : Any> run(
        stage: String,
        block: suspend (ExecutionLease) -> T,
    ): T {
        val startedAtNanos = clock.monotonicNanos()
        semaphore.acquire()
        val lease = DefaultExecutionLease(
            stage = stage,
            semaphore = semaphore,
            ioDispatcher = ioDispatcher,
            observer = observer,
        )
        try {
            val waitedMs = ((clock.monotonicNanos() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
            observer.emit(SessionEvent.SerialPermitAcquired(stage = stage, waitedMs = waitedMs))
            return block(lease)
        } finally {
            lease.requestRelease()
        }
    }
}

/**
 * Ref-counted permit lease ensuring blocking OAuth calls and `NonCancellable` persistence fully
 * unwind before the underlying [Semaphore] permit is handed to the next waiter.
 */
internal class DefaultExecutionLease(
    private val stage: String,
    private val semaphore: Semaphore,
    private val ioDispatcher: CoroutineDispatcher,
    private val observer: SafeSessionObserver,
) : ExecutionLease {

    private val stateLock = Any()
    private var activeBlockingWork = 0
    private var releaseRequested = false
    private var released = false

    override suspend fun <T : Any> guarded(block: suspend () -> T): T {
        if (!beginBlockingWork()) {
            throw CancellationException("nana7mi serial lease already released")
        }
        try {
            return withContext(ioDispatcher) { block() }
        } finally {
            endBlockingWork()
        }
    }

    fun requestRelease() {
        val releaseNow = synchronized(stateLock) {
            releaseRequested = true
            markReleasedIfIdle()
        }
        if (releaseNow) releasePermit()
    }

    private fun beginBlockingWork(): Boolean = synchronized(stateLock) {
        if (releaseRequested) return@synchronized false
        activeBlockingWork += 1
        true
    }

    private fun endBlockingWork() {
        val releaseNow = synchronized(stateLock) {
            check(activeBlockingWork > 0) { "activeBlockingWork underflow" }
            activeBlockingWork -= 1
            markReleasedIfIdle()
        }
        if (releaseNow) releasePermit()
    }

    private fun markReleasedIfIdle(): Boolean {
        if (!releaseRequested || activeBlockingWork != 0 || released) return false
        released = true
        return true
    }

    private fun releasePermit() {
        semaphore.release()
        observer.emit(SessionEvent.SerialPermitReleased(stage = stage))
    }
}
