package ceui.pixiv.chat.api

import ceui.pixiv.chat.core.ChatMessageStream
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.SendState
import ceui.pixiv.websocket.IncomingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * [ChatMessageStream] over a [Flow] of [IncomingMessage] frames.
 *
 * The stream sits on top of [ceui.pixiv.websocket.WebSocketManager.incoming]
 * (a single app-scoped WS that receives **all** rooms the authed user is
 * part of). Each subscriber filters by its target `room` field — one
 * underlying socket, N consumer-side filters.
 *
 * Side channels (always-on, fed by [routeSideChannels]) expose hello /
 * err frames that aren't room-scoped.
 */
class WsChatMessageStream(
    private val incoming: Flow<IncomingMessage>,
) : ChatMessageStream<ChatMessageEntity> {

    private val _helloFrames = MutableSharedFlow<ChatFrame.Hello>(
        replay = 1, extraBufferCapacity = 1,
    )
    val helloFrames: Flow<ChatFrame.Hello> get() = _helloFrames

    private val _errorFrames = MutableSharedFlow<ChatFrame.Err>(
        replay = 0, extraBufferCapacity = 8,
    )
    val errorFrames: Flow<ChatFrame.Err> get() = _errorFrames

    // Typing frames bypass `observe(room)` — they aren't part of the
    // persisted ChatMessageEntity stream that the RecyclerView renders,
    // but consumers (VM) want a room-filtered Flow. extraBufferCapacity=16
    // because typing fires more often than err/hello (peer typing at ~1/s
    // sustained); 16 is comfortable headroom for short bursts.
    private val _typingFrames = MutableSharedFlow<ChatFrame.Typing>(
        replay = 0, extraBufferCapacity = 16,
    )
    val typingFrames: Flow<ChatFrame.Typing> get() = _typingFrames

    // Public-room send switch changes, pushed to all connections. replay=1 so a
    // subscriber that arrives after the toggle still sees the latest state.
    private val _globalSendStateFrames = MutableSharedFlow<ChatFrame.GlobalSendState>(
        replay = 1, extraBufferCapacity = 1,
    )
    val globalSendStateFrames: Flow<ChatFrame.GlobalSendState> get() = _globalSendStateFrames

    override fun observe(room: String): Flow<ChatMessageEntity> =
        incoming
            .filterIsInstance<IncomingMessage.Text>()
            .map { ChatFrameDecoder.decode(it.text) }
            .filterIsInstance<ChatFrame.Msg>()
            .filter { it.room == room }
            .mapNotNull(::toEntity)

    /**
     * Start always-on side-channel routing on [scope] (the app-scoped gateway
     * scope). MUST be driven independently of [observe] — side channels (hello /
     * err / typing / global_send_state) are connection-level, not room-level.
     * Crucially the `hello` arrives at handshake, long before any chat fragment
     * opens a room; if routing were tied to [observe] (as it used to be) that
     * hello — and its `global_send_enabled` — would be missed entirely, leaving
     * the public-room send gate stuck at its default. Decoding here is separate
     * from [observe]'s own decode (manager.incoming is a fan-out SharedFlow);
     * routing lives ONLY here so each side frame is emitted exactly once.
     */
    fun startSideChannelRouting(scope: CoroutineScope) {
        incoming
            .filterIsInstance<IncomingMessage.Text>()
            .onEach { routeSideChannels(ChatFrameDecoder.decode(it.text)) }
            .launchIn(scope)
    }

    /**
     * Hello / err / typing / global_send_state frames are not room-scoped — they
     * go to dedicated side flows so any subscriber (gateway state, fragment UI
     * bindings, always-on logger) can pick them up independent of which room a
     * fragment happens to be watching. Driven always-on by [startSideChannelRouting].
     */
    private fun routeSideChannels(frame: ChatFrame) {
        when (frame) {
            is ChatFrame.Hello -> {
                Timber.tag(TAG).i(
                    "⇣ hello uid=%d name=%s server_ts=%d",
                    frame.uid, frame.displayName, frame.serverTs,
                )
                _helloFrames.tryEmit(frame)
            }
            is ChatFrame.Msg -> {
                // Log all received msg frames (regardless of which room
                // they belong to) so the always-on observer in the gateway
                // can see traffic without subscribing to a specific room.
                Timber.tag(TAG).i(
                    "⇣ msg room=%s ts=%d uid=%d name=%s cmid=%s illust=%s text=%s",
                    frame.room, frame.ts, frame.uid,
                    frame.displayName ?: "-",
                    frame.clientMsgId ?: "-",
                    frame.illustId?.toString() ?: "-",
                    frame.text?.take(80) ?: "-",
                )
            }
            is ChatFrame.Err -> {
                Timber.tag(TAG).w("⇣ err code=%s cmid=%s", frame.code, frame.clientMsgId ?: "-")
                _errorFrames.tryEmit(frame)
            }
            is ChatFrame.Typing -> {
                Timber.tag(TAG).d(
                    "⇣ typing room=%s uid=%d state=%s name=%s",
                    frame.room, frame.uid, frame.state, frame.displayName ?: "-",
                )
                _typingFrames.tryEmit(frame)
            }
            is ChatFrame.GlobalSendState -> {
                Timber.tag(TAG).i("⇣ global_send_state enabled=%b", frame.enabled)
                _globalSendStateFrames.tryEmit(frame)
            }
            is ChatFrame.Pong -> Timber.tag(TAG).d("⇣ pong server_ts=%d", frame.serverTs)
            is ChatFrame.Unknown -> Timber.tag(TAG).d("⇣ unknown frame dead-lettered")
        }
    }

    private fun toEntity(f: ChatFrame.Msg): ChatMessageEntity? = f.toChatMessageEntity()

    companion object {
        private const val TAG = "Chat-Stream"
    }
}

/**
 * Map a server-broadcast `msg` frame to a Room entity. Shared between the
 * gateway's always-on persister and [WsChatMessageStream]'s per-room
 * fragment observer — both call this so the wire→entity mapping has
 * exactly one definition.
 *
 * Returns `null` when [ChatFrame.Msg.clientMsgId] is absent: without it
 * we have no stable dedup key against the optimistic-send row, and
 * synthesizing a per-frame UUID would always produce a unique key →
 * **never dedup** → UPSERT degenerates into INSERT, doubling rows.
 * Dropping the frame is the safer behaviour.
 */
internal fun ChatFrame.Msg.toChatMessageEntity(): ChatMessageEntity? {
    val cmid = clientMsgId ?: run {
        Timber.tag("Chat-Stream").w(
            "msg frame without client_msg_id, dropping (ts=%d uid=%d room=%s)",
            ts, uid, room,
        )
        return null
    }
    return ChatMessageEntity(
        localKey = cmid,
        serverId = null,
        clientMsgId = cmid,
        uid = uid,
        room = room,
        displayName = displayName,
        text = text,
        illustId = illustId,
        ts = ts,
        state = SendState.Delivered,
        replyToUid = replyTo?.uid,
        replyToCmid = replyTo?.clientMsgId,
        replyToDisplayName = replyTo?.displayName,
        replyToText = replyTo?.text,
    )
}
