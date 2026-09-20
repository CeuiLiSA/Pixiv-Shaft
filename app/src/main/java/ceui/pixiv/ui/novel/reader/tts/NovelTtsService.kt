package ceui.pixiv.ui.novel.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import ceui.lisa.R
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import java.util.Locale

/**
 * System TTS playback that survives the reader Fragment being stopped. The
 * service owns the TextToSpeech lifecycle and broadcasts only small state
 * updates back to visible reader instances. TextToSpeech owns synthesis and
 * its utterance queue, so this path uses a typed mediaPlayback foreground
 * service directly instead of wrapping it in a fake Media3 Player.
 */
class NovelTtsService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var segments: List<NovelTtsText.Segment> = emptyList()
    private var currentIndex = 0
    private var generation = 0
    private var title = ""
    private var speed = 1f
    private var pitch = 1f
    private var engine: String? = null
    private var voice: String? = null
    private var initialized = false
    private var state = NovelTtsController.STATE_IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> stopSpeech()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> pauseSpeech()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action != NovelTtsController.ACTION_START && tts == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (action) {
            NovelTtsController.ACTION_START -> startSpeech(intent)
            NovelTtsController.ACTION_PAUSE -> pauseSpeech()
            NovelTtsController.ACTION_RESUME -> resumeSpeech()
            NovelTtsController.ACTION_STOP -> stopSpeech()
            NovelTtsController.ACTION_SET_SPEED -> {
                speed = intent.getFloatExtra(NovelTtsController.EXTRA_SPEED, speed).coerceIn(0.5f, 2f)
                ReaderSettings.ttsSpeed = speed
                tts?.setSpeechRate(speed)
                publishState()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSpeech(intent: Intent) {
        title = intent.getStringExtra(NovelTtsController.EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(NovelTtsController.EXTRA_TEXT).orEmpty()
        segments = NovelTtsText.split(text)
        currentIndex = 0
        speed = intent.getFloatExtra(NovelTtsController.EXTRA_SPEED, 1f).coerceIn(0.5f, 2f)
        pitch = intent.getFloatExtra(NovelTtsController.EXTRA_PITCH, 1f).coerceIn(0.5f, 2f)
        engine = intent.getStringExtra(NovelTtsController.EXTRA_ENGINE)?.takeIf { it.isNotBlank() }
        voice = intent.getStringExtra(NovelTtsController.EXTRA_VOICE)?.takeIf { it.isNotBlank() }
        ReaderSettings.ttsSpeed = speed
        if (segments.isEmpty()) {
            publishError(getString(R.string.reader_tts_empty))
            return
        }
        if (tts == null || !sameEngine()) {
            releaseTts()
            initialized = false
            tts = runCatching {
                if (engine == null) TextToSpeech(this, this) else TextToSpeech(this, this, engine)
            }.getOrElse {
                publishError(getString(R.string.reader_tts_init_failed))
                return
            }
        } else if (initialized) {
            configureTts()
            queueFromCurrent()
        }
        publishState()
    }

    private fun sameEngine(): Boolean = engine == activeEngine

    private var activeEngine: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            publishError(getString(R.string.reader_tts_init_failed))
            return
        }
        initialized = true
        activeEngine = engine
        tts?.setOnUtteranceProgressListener(listener)
        tts?.language = Locale.getDefault()
        configureTts()
        queueFromCurrent()
    }

    private fun configureTts() {
        tts?.setPitch(pitch)
        tts?.setSpeechRate(speed)
        voice?.let { requested ->
            tts?.voices?.firstOrNull { it.name == requested }?.let { tts?.voice = it }
        }
    }

    private fun queueFromCurrent() {
        val speaker = tts ?: return
        if (!initialized || segments.isEmpty()) return
        if (!requestAudioFocus()) {
            state = NovelTtsController.STATE_PAUSED
            publishState()
            return
        }
        generation++
        val queueGeneration = generation
        speaker.stop()
        state = NovelTtsController.STATE_PLAYING
        if (!speakCurrentSegment(queueGeneration, TextToSpeech.QUEUE_FLUSH)) {
            return
        }
        publishState()
    }

    /** Queue one bounded utterance at a time so long novels do not overflow an engine queue. */
    private fun speakCurrentSegment(queueGeneration: Int = generation, queueMode: Int): Boolean {
        val speaker = tts ?: return false
        val segment = segments.getOrNull(currentIndex) ?: return false
        val id = utteranceId(queueGeneration, currentIndex)
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        if (speaker.speak(segment.text, queueMode, params, id) == TextToSpeech.ERROR) {
            publishError(getString(R.string.reader_tts_speak_failed))
            return false
        }
        return true
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post {
                parseUtterance(utteranceId)?.let { (_, index) ->
                    currentIndex = index
                    state = NovelTtsController.STATE_PLAYING
                    publishState()
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                parseUtterance(utteranceId)?.let { (_, index) ->
                    if (index >= segments.lastIndex) {
                        state = NovelTtsController.STATE_IDLE
                        currentIndex = segments.size
                        publishState()
                        stopSelf()
                    } else {
                        currentIndex = index + 1
                        speakCurrentSegment(queueMode = TextToSpeech.QUEUE_FLUSH)
                        publishState()
                    }
                }
            }
        }

        override fun onError(utteranceId: String?) {
            mainHandler.post {
                parseUtterance(utteranceId)?.let { publishError(getString(R.string.reader_tts_speak_failed)) }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) = Unit
    }

    private fun pauseSpeech() {
        if (state != NovelTtsController.STATE_PLAYING) return
        generation++
        tts?.stop()
        abandonAudioFocus()
        state = NovelTtsController.STATE_PAUSED
        publishState()
    }

    private fun resumeSpeech() {
        if (state != NovelTtsController.STATE_PAUSED) return
        queueFromCurrent()
    }

    private fun stopSpeech() {
        generation++
        state = NovelTtsController.STATE_IDLE
        tts?.stop()
        abandonAudioFocus()
        publishState()
        stopSelf()
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun parseUtterance(id: String?): Pair<Int, Int>? {
        val parts = id?.split('-') ?: return null
        if (parts.size != 3 || parts[0] != UTTERANCE_PREFIX) return null
        val g = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return if (g == generation) g to index else null
    }

    private fun utteranceId(queueGeneration: Int, index: Int) = "$UTTERANCE_PREFIX-$queueGeneration-$index"

    private fun publishState() {
        NovelTtsController.lastState = state
        sendBroadcast(Intent(NovelTtsController.ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(NovelTtsController.EXTRA_STATE, state)
            putExtra(NovelTtsController.EXTRA_INDEX, currentIndex)
            putExtra(NovelTtsController.EXTRA_TOTAL, segments.size)
        })
        if (state != NovelTtsController.STATE_ERROR) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
        }
    }

    private fun publishError(message: String) {
        state = NovelTtsController.STATE_ERROR
        NovelTtsController.lastState = state
        sendBroadcast(Intent(NovelTtsController.ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(NovelTtsController.EXTRA_STATE, state)
            putExtra(NovelTtsController.EXTRA_ERROR, message)
        })
        stopSelf()
    }

    private fun notification(): Notification {
        val toggleAction = if (state == NovelTtsController.STATE_PLAYING) {
            NovelTtsController.ACTION_PAUSE to R.drawable.ic_baseline_pause_24
        } else {
            NovelTtsController.ACTION_RESUME to R.drawable.ic_baseline_play_arrow_24
        }
        val toggleIntent = PendingIntent.getService(
            this, 1, commandIntent(toggleAction.first), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 2, commandIntent(NovelTtsController.ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_play_arrow_24)
            .setContentTitle(title.ifBlank { getString(R.string.reader_tts_title) })
            .setContentText(getString(R.string.reader_tts_progress, currentIndex.coerceAtMost(segments.size), segments.size))
            .setOngoing(state == NovelTtsController.STATE_PLAYING || state == NovelTtsController.STATE_PAUSED)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(toggleAction.second, getString(if (state == NovelTtsController.STATE_PLAYING) R.string.reader_tts_pause else R.string.reader_tts_resume), toggleIntent)
            .addAction(R.drawable.spark_ic_stop, getString(R.string.reader_tts_stop), stopIntent)
            .build()
    }

    private fun commandIntent(action: String) = Intent(this, NovelTtsService::class.java).setAction(action)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.reader_tts_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun releaseTts() {
        tts?.runCatching {
            stop()
            shutdown()
        }
        tts = null
        activeEngine = null
    }

    override fun onDestroy() {
        abandonAudioFocus()
        releaseTts()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "novel_tts"
        private const val NOTIFICATION_ID = 0x4E5454
        private const val UTTERANCE_PREFIX = "shafttts"
    }
}
