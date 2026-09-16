package ceui.pixiv.communication.internal

import ceui.pixiv.communication.CommunicationDiagnostic
import ceui.pixiv.communication.CommunicationObserver

internal class TopicDiagnostics(private val observer: CommunicationObserver) {
    fun report(event: CommunicationDiagnostic) {
        try {
            observer.onDiagnostic(event)
        } catch (_: Exception) {
            // Optional diagnostics must not change an already committed publication. Fatal JVM
            // Errors are deliberately not swallowed. Observers must provide their own logging.
        }
    }
}
