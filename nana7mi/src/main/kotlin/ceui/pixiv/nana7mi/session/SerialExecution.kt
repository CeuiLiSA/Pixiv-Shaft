package ceui.pixiv.nana7mi.session

import ceui.pixiv.nana7mi.session.internal.DefaultSerialExecutionCoordinator
import ceui.pixiv.nana7mi.session.internal.SafeSessionObserver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Active serial-execution lease that keeps the coordinator's permit alive while blocking token
 * refresh or `NonCancellable` persistence unwinds after coroutine cancellation.
 */
public interface ExecutionLease {
    /**
     * Runs [block] on the coordinator's IO dispatcher under the lease.
     *
     * Throws [kotlinx.coroutines.CancellationException] without running [block] if the lease has
     * already begun releasing. Once entered, the underlying serial permit cannot be handed to the
     * next waiter until [block] has actually returned.
     */
    public suspend fun <T : Any> guarded(block: suspend () -> T): T
}

/**
 * Fair FIFO permit coordinator serializing borrowed-account flows across callers (for example
 * illustration and novel search tabs, or concurrent pagination calls).
 *
 * Unlike a global singleton, instances are ordinary objects constructed at the composition root
 * and injected where needed.
 */
public interface SerialExecutionCoordinator {
    /**
     * Suspends in FIFO order until a permit is acquired, invokes [block] with an [ExecutionLease],
     * and releases the permit only after both [block] and any [ExecutionLease.guarded] work inside
     * it have fully exited.
     */
    public suspend fun <T : Any> run(
        stage: String,
        block: suspend (ExecutionLease) -> T,
    ): T
}

/** Creates a new fair [SerialExecutionCoordinator] with the specified IO dispatcher, clock, and observer. */
@JvmOverloads
public fun SerialExecutionCoordinator(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: SessionClock = SessionClock.SYSTEM,
    observer: SessionObserver = SessionObserver.NO_OP,
): SerialExecutionCoordinator = DefaultSerialExecutionCoordinator(
    ioDispatcher = ioDispatcher,
    clock = clock,
    observer = SafeSessionObserver(observer),
)
