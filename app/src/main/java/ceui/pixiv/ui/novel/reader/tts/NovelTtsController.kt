package ceui.pixiv.ui.novel.reader.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.UUID

/** Small commands cross Binder; prepared text stays in this non-sticky service's process. */
object NovelTtsController {
    const val ACTION_START = "ceui.pixiv.novel.tts.START"
    const val ACTION_PAUSE = "ceui.pixiv.novel.tts.PAUSE"
    const val ACTION_RESUME = "ceui.pixiv.novel.tts.RESUME"
    const val ACTION_STOP = "ceui.pixiv.novel.tts.STOP"
    const val ACTION_SET_SPEED = "ceui.pixiv.novel.tts.SET_SPEED"
    const val ACTION_STATE = "ceui.pixiv.novel.tts.STATE"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_STATE = "state"
    const val EXTRA_ERROR = "error"
    const val EXTRA_PAYLOAD_ID = "payload_id"
    const val EXTRA_SESSION_ID = "session_id"

    const val STATE_IDLE = "idle"
    const val STATE_PREPARING = "preparing"
    const val STATE_PLAYING = "playing"
    const val STATE_PAUSED = "paused"
    const val STATE_ERROR = "error"

    data class PlaybackState(
        val sessionId: String? = null,
        val state: String = STATE_IDLE,
        val sourceRange: IntRange? = null,
    ) {
        fun forSession(id: String): String = if (sessionId == id) state else STATE_IDLE
        val isActive: Boolean
            get() = state == STATE_PREPARING || state == STATE_PLAYING || state == STATE_PAUSED
    }

    // Only the service writes playback state. A delayed UI broadcast must not
    // overwrite newer state; menus read this snapshot even after view recreation.
    @Volatile
    var playbackState = PlaybackState()
        internal set

    fun start(
        context: Context,
        sessionId: String,
        title: String,
        segments: List<NovelTtsText.Segment>,
        speed: Float,
        pitch: Float,
        engine: String?,
        voice: String?,
        localeTag: String? = null,
    ) {
        val payloadId = NovelTtsPayloadStore.put(
            NovelTtsPayloadStore.Payload(sessionId, title, segments, speed, pitch, engine, voice, localeTag),
        )
        var sent = false
        try {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context, NovelTtsService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_PAYLOAD_ID, payloadId)
                },
            )
            sent = true
        } finally {
            if (!sent) NovelTtsPayloadStore.take(payloadId)
        }
    }

    fun pause(context: Context, sessionId: String) = send(context, ACTION_PAUSE, sessionId)
    fun resume(context: Context, sessionId: String) = send(context, ACTION_RESUME, sessionId)
    fun stop(context: Context, sessionId: String) = send(context, ACTION_STOP, sessionId)
    fun setSpeed(context: Context, sessionId: String, speed: Float) =
        send(context, ACTION_SET_SPEED, sessionId, speed)

    private fun send(context: Context, action: String, sessionId: String, speed: Float = 1f) {
        context.applicationContext.startService(Intent(context, NovelTtsService::class.java).apply {
            this.action = action
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_SPEED, speed)
        })
    }
}

/** Only the latest pending start is retained, then consumed exactly once. */
internal object NovelTtsPayloadStore {
    data class Payload(
        val sessionId: String,
        val title: String,
        val segments: List<NovelTtsText.Segment>,
        val speed: Float,
        val pitch: Float,
        val engine: String?,
        val voice: String?,
        val localeTag: String? = null,
    )

    private var pending: Pair<String, Payload>? = null

    @Synchronized
    fun put(payload: Payload): String = UUID.randomUUID().toString().also {
        pending = it to payload
    }

    @Synchronized
    fun take(id: String): Payload? {
        val value = pending?.takeIf { it.first == id } ?: return null
        pending = null
        return value.second
    }

    @Synchronized
    fun hasPending(): Boolean = pending != null
}
