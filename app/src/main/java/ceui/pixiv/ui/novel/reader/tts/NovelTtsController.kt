package ceui.pixiv.ui.novel.reader.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Intent boundary used by the reader UI and the process-scoped foreground service. */
object NovelTtsController {
    const val ACTION_START = "ceui.pixiv.novel.tts.START"
    const val ACTION_PAUSE = "ceui.pixiv.novel.tts.PAUSE"
    const val ACTION_RESUME = "ceui.pixiv.novel.tts.RESUME"
    const val ACTION_STOP = "ceui.pixiv.novel.tts.STOP"
    const val ACTION_SET_SPEED = "ceui.pixiv.novel.tts.SET_SPEED"
    const val ACTION_STATE = "ceui.pixiv.novel.tts.STATE"

    @Volatile
    var lastState: String = STATE_IDLE

    const val EXTRA_TEXT = "text"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_PITCH = "pitch"
    const val EXTRA_ENGINE = "engine"
    const val EXTRA_VOICE = "voice"
    const val EXTRA_STATE = "state"
    const val EXTRA_INDEX = "index"
    const val EXTRA_TOTAL = "total"
    const val EXTRA_ERROR = "error"

    const val STATE_IDLE = "idle"
    const val STATE_PLAYING = "playing"
    const val STATE_PAUSED = "paused"
    const val STATE_ERROR = "error"

    fun start(
        context: Context,
        title: String,
        text: String,
        speed: Float,
        pitch: Float,
        engine: String?,
        voice: String?,
    ) = send(context, Intent(context, NovelTtsService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_TEXT, text)
        putExtra(EXTRA_SPEED, speed)
        putExtra(EXTRA_PITCH, pitch)
        putExtra(EXTRA_ENGINE, engine)
        putExtra(EXTRA_VOICE, voice)
    })

    fun pause(context: Context) = send(context, command(context, ACTION_PAUSE))

    fun resume(context: Context) = send(context, command(context, ACTION_RESUME))

    fun stop(context: Context) = send(context, command(context, ACTION_STOP))

    fun setSpeed(context: Context, speed: Float) = send(context, command(context, ACTION_SET_SPEED).apply {
        putExtra(EXTRA_SPEED, speed)
    })

    fun updateState(intent: Intent) {
        lastState = intent.getStringExtra(EXTRA_STATE) ?: STATE_IDLE
    }

    private fun command(context: Context, action: String) = Intent().apply {
        component = android.content.ComponentName(context, NovelTtsService::class.java)
        this.action = action
    }

    private fun send(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.component == null) {
            intent.component = android.content.ComponentName(appContext, NovelTtsService::class.java)
        }
        if (intent.action == ACTION_START) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.startService(intent)
        }
    }
}
