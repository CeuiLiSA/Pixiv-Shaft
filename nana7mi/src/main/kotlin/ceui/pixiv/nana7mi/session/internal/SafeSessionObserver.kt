package ceui.pixiv.nana7mi.session.internal

import ceui.pixiv.nana7mi.session.SessionEvent
import ceui.pixiv.nana7mi.session.SessionObserver
import kotlinx.coroutines.CancellationException

/**
 * Wraps a caller-supplied [SessionObserver] so observer faults never abort session control flow
 * or prevent serial permit release. Fatal JVM [Error]s and [CancellationException]s still propagate.
 */
internal class SafeSessionObserver(
    private val delegate: SessionObserver,
) {
    fun emit(event: SessionEvent) {
        if (delegate === SessionObserver.NO_OP) return
        try {
            delegate.onEvent(event)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Diagnostics must never alter session outcomes or leak permits.
        }
    }
}
