package ceui.pixiv.websocket

/**
 * Decides what happens when the outgoing message buffer is full.
 *
 * The buffer fills when callers issue `send(...)` faster than the wire can
 * drain — typically while the socket is disconnected and reconnecting, or
 * during a saturated upload.
 *
 * Pick the strategy that matches your data semantics:
 *
 *  - **Telemetry / position updates / sensor data** → [DropOldest]. The
 *    latest reading is what matters; stale readings are useless.
 *  - **Ordered protocols where every message matters** but you can't afford
 *    to suspend the caller (e.g. UI thread) → [DropNewest]. The caller
 *    sees a `false` return and can decide what to do (retry later, surface
 *    an error, …).
 *  - **Business-critical messages** where the caller is already in a
 *    coroutine and is OK suspending until there's room → [Suspend].
 *
 * The strategy interacts with the two `send` methods like this:
 *
 * | strategy    | `send()` (non-suspending)         | `sendSuspending()` (suspending) |
 * |-------------|-----------------------------------|---------------------------------|
 * | DropOldest  | always returns `true`, drops oldest | same as send()                 |
 * | DropNewest  | returns `false` when full         | same as send()                  |
 * | Suspend     | returns `false` when full         | suspends until room is available |
 */
enum class BackpressureStrategy {

    /**
     * Drop the **oldest** queued message to make room for the new one.
     * Lossy but never blocks. `send()` always returns `true`.
     */
    DropOldest,

    /**
     * Reject the **new** message; keep the existing buffer intact.
     * Lossy but signals the loss to the caller. `send()` returns `false`
     * when the buffer is full.
     */
    DropNewest,

    /**
     * Suspend the caller until room is available. Only meaningful for
     * [RobustWebSocketClient.sendSuspending] — the non-suspending
     * `send(...)` overloads still return `false` immediately rather than
     * blocking the calling thread.
     */
    Suspend,
}
