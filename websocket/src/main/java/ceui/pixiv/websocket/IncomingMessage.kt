package ceui.pixiv.websocket

import okio.ByteString

/**
 * An inbound business message delivered on [WebSocketClient.incoming].
 *
 * Kept as a **separate type** from [WebSocketEvent] so that the two concerns
 * cannot be silently conflated at the call site:
 *
 *  - [WebSocketEvent] is **observability** — lifecycle transitions
 *    (`Open`, `Closing`, `Closed`, `Failure`, `ReconnectScheduled`) that a
 *    UI layer shows to the user. Overflow drops the oldest events; a slow
 *    observability subscriber losing an `Open` event is an acceptable cost.
 *
 *  - [IncomingMessage] is **data** — frames the server sent to the app that
 *    are meant for business logic. Mixing these into `events` is the classic
 *    way to write code that compiles, runs, and *silently drops messages*
 *    when the consumer can't keep up. Giving them their own type forces the
 *    call site to pick the right flow, and the separate buffer policy on
 *    [WebSocketClient.incoming] favours correctness over fire-and-forget
 *    semantics.
 *
 * Even with [WebSocketClient.incoming]'s larger buffer and louder overflow
 * behaviour, delivery is still **best-effort** — a consumer that parks
 * forever can cause the backing buffer to fill, and the emitter will drop
 * frames rather than block the OkHttp dispatcher. Business code that cannot
 * afford to lose a single message must implement application-level
 * acknowledgements on top.
 */
sealed class IncomingMessage {

    /** Server sent a text frame. */
    data class Text(val text: String) : IncomingMessage()

    /** Server sent a binary frame. */
    data class Binary(val bytes: ByteString) : IncomingMessage()
}
