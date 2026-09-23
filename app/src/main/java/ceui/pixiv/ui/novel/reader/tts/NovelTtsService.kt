package ceui.pixiv.ui.novel.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import ceui.lisa.R
import timber.log.Timber
import java.util.Locale

/**
 * Owns platform TTS and its queue for background reading. All state mutations
 * run on Main; engine initialization and each utterance have separate tokens.
 */
class NovelTtsService : Service() {
    private var tts: TextToSpeech? = null
    private var segments: List<NovelTtsText.Segment> = emptyList()
    private var currentIndex = 0
    private var currentOffset = 0
    private var utteranceOffset = 0
    private var sourceRange: IntRange? = null
    private var generation = 0L
    private var engineGeneration = 0L
    private var activeUtterance: String? = null
    private var title = ""
    private var sessionId: String? = null
    private var speed = 1f
    private var pitch = 1f
    private var voice: String? = null
    private var localeTag: String? = null
    private var initialized = false
    private var destroyed = false
    private var foreground = false
    private var state = NovelTtsController.STATE_IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusGeneration = 0L
    private var hasAudioFocus = false
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseSpeech()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.reader_tts_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        ContextCompat.registerReceiver(this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (action == NovelTtsController.ACTION_START) {
            val payload = intent.getStringExtra(NovelTtsController.EXTRA_PAYLOAD_ID)?.let(NovelTtsPayloadStore::take)
            if (payload != null) startSpeech(payload)
            // A newer start may have replaced this token before delivery.
            else if (sessionId == null && !NovelTtsPayloadStore.hasPending()) stopSelf(startId)
        } else if (sessionId == null) {
            stopSelf(startId)
        } else if (intent.getStringExtra(NovelTtsController.EXTRA_SESSION_ID) == sessionId) {
            when (action) {
                NovelTtsController.ACTION_PAUSE -> pauseSpeech()
                NovelTtsController.ACTION_RESUME -> resumeSpeech()
                NovelTtsController.ACTION_STOP -> finishSpeech()
                NovelTtsController.ACTION_SET_SPEED -> {
                    speed = intent.getFloatExtra(NovelTtsController.EXTRA_SPEED, speed).coerceIn(0.5f, 2f)
                    if (initialized) tts?.setSpeechRate(speed)
                    if (state == NovelTtsController.STATE_PLAYING) queueFromCurrent()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startSpeech(payload: NovelTtsPayloadStore.Payload) {
        invalidateUtterance()
        releaseTts()
        abandonAudioFocus()
        sessionId = payload.sessionId
        title = payload.title
        segments = payload.segments
        currentIndex = 0
        currentOffset = 0
        sourceRange = null
        speed = payload.speed.coerceIn(0.5f, 2f)
        pitch = payload.pitch.coerceIn(0.5f, 2f)
        voice = payload.voice?.takeIf { it.isNotBlank() }
        localeTag = payload.localeTag?.takeIf { it.isNotBlank() }
        state = NovelTtsController.STATE_PREPARING
        // Foreground promotion precedes initialization / focus requests (API 35+).
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foreground = true
        } catch (ex: IllegalStateException) {
            Timber.w(ex, "TTS foreground start rejected")
            finishSpeech(getString(R.string.reader_tts_init_failed))
            return
        } catch (ex: SecurityException) {
            Timber.w(ex, "TTS foreground permission rejected")
            finishSpeech(getString(R.string.reader_tts_init_failed))
            return
        }
        if (segments.isEmpty()) {
            finishSpeech(getString(R.string.reader_tts_empty))
            return
        }
        publishState()
        val initGeneration = engineGeneration
        val engine = payload.engine?.takeIf { it.isNotBlank() }
        try {
            val onInit = TextToSpeech.OnInitListener { status ->
                // Some implementations call synchronously during construction;
                // posting also ensures tts has been assigned before use.
                mainHandler.post {
                    if (!destroyed && initGeneration == engineGeneration) onTtsInitialized(status)
                }
            }
            tts = if (engine == null) TextToSpeech(this, onInit) else TextToSpeech(this, onInit, engine)
        } catch (ex: RuntimeException) {
            Timber.w(ex, "TTS initialization failed")
            finishSpeech(getString(R.string.reader_tts_init_failed))
        }
    }

    private fun onTtsInitialized(status: Int) {
        if (initialized) return
        if (status != TextToSpeech.SUCCESS) {
            finishSpeech(getString(R.string.reader_tts_init_failed))
            return
        }
        try {
            initialized = true
            tts?.setOnUtteranceProgressListener(listener)
            tts?.setAudioAttributes(audioAttributes)
            val requestedLocale = localeTag?.let(Locale::forLanguageTag)
            if (requestedLocale != null) {
                val availability = tts?.isLanguageAvailable(requestedLocale)
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                val setResult = tts?.setLanguage(requestedLocale) ?: TextToSpeech.ERROR
                if (availability < TextToSpeech.LANG_AVAILABLE || setResult < 0) {
                    finishSpeech(getString(R.string.reader_tts_language_unavailable))
                    return
                }
            }
            tts?.setPitch(pitch)
            tts?.setSpeechRate(speed)
            // A stored voice must belong to the requested language. Otherwise a
            // previous Chinese voice selection could override Japanese detection.
            voice?.let { requested ->
                tts?.voices?.firstOrNull {
                    it.name == requested &&
                        (requestedLocale == null || it.locale.language == requestedLocale.language)
                }?.let { tts?.voice = it }
            }
        } catch (ex: RuntimeException) {
            Timber.w(ex, "TTS configuration failed")
            finishSpeech(getString(R.string.reader_tts_init_failed))
            return
        }
        if (state == NovelTtsController.STATE_PREPARING) queueFromCurrent()
    }

    private fun queueFromCurrent() {
        val speaker = tts ?: return
        if (!initialized || segments.isEmpty()) return
        invalidateUtterance()
        speaker.stop()
        if (!requestAudioFocus()) {
            state = NovelTtsController.STATE_PAUSED
            publishState()
            return
        }
        state = NovelTtsController.STATE_PLAYING
        if (speakCurrentSegment()) publishState()
    }

    /** Only one short utterance is outstanding; no unbounded engine queue. */
    private fun speakCurrentSegment(): Boolean {
        val segment = segments.getOrNull(currentIndex) ?: return false
        utteranceOffset = currentOffset
        val id = "shafttts-${++generation}"
        activeUtterance = id
        val result = runCatching {
            tts?.speak(segment.text.substring(utteranceOffset), TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        }.getOrElse { ex ->
            Timber.w(ex, "TTS utterance rejected")
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            finishSpeech(getString(R.string.reader_tts_speak_failed))
            return false
        }
        return true
    }

    private fun isCurrent(id: String?): Boolean =
        !destroyed && state == NovelTtsController.STATE_PLAYING && id != null && id == activeUtterance

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post {
                if (!isCurrent(utteranceId)) return@post
                sourceRange = segments[currentIndex].sourceRange(utteranceOffset)
                publishState(updateNotification = false)
            }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                if (!isCurrent(utteranceId)) return@post
                activeUtterance = null
                currentIndex++
                currentOffset = 0
                if (currentIndex >= segments.size) finishSpeech()
                else if (speakCurrentSegment()) publishState()
            }
        }

        override fun onError(utteranceId: String?) {
            mainHandler.post {
                if (isCurrent(utteranceId)) finishSpeech(getString(R.string.reader_tts_speak_failed))
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            mainHandler.post {
                if (!isCurrent(utteranceId)) return@post
                val length = segments[currentIndex].text.length - utteranceOffset
                if (start in 0 until length && end in (start + 1)..length) {
                    currentOffset = maxOf(currentOffset, utteranceOffset + start)
                    sourceRange = segments[currentIndex].sourceRange(utteranceOffset + start, utteranceOffset + end)
                    publishState(updateNotification = false)
                }
            }
        }
    }

    private fun invalidateUtterance() {
        generation++
        activeUtterance = null
    }

    private fun pauseSpeech() {
        if (state != NovelTtsController.STATE_PLAYING && state != NovelTtsController.STATE_PREPARING) return
        invalidateUtterance()
        if (initialized) tts?.stop()
        abandonAudioFocus()
        state = NovelTtsController.STATE_PAUSED
        publishState()
    }

    private fun resumeSpeech() {
        if (state != NovelTtsController.STATE_PAUSED) return
        state = NovelTtsController.STATE_PREPARING
        if (initialized) queueFromCurrent() else publishState()
    }

    private fun finishSpeech(error: String? = null) {
        invalidateUtterance()
        releaseTts()
        abandonAudioFocus()
        segments = emptyList()
        sourceRange = null
        state = if (error == null) NovelTtsController.STATE_IDLE else NovelTtsController.STATE_ERROR
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishState(error)
        stopSelf()
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val manager = audioManager ?: return false
        val token = ++focusGeneration
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            mainHandler.post {
                if (destroyed || token != focusGeneration) return@post
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> finishSpeech()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseSpeech()
                }
            }
        }
        audioFocusListener = listener
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener, mainHandler)
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) abandonAudioFocus()
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        focusGeneration++
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioFocusListener?.let { audioManager?.abandonAudioFocus(it) }
        }
        audioFocusListener = null
        hasAudioFocus = false
    }

    private fun publishState(error: String? = null, updateNotification: Boolean = true) {
        NovelTtsController.playbackState = NovelTtsController.PlaybackState(sessionId, state, sourceRange)
        sendBroadcast(Intent(NovelTtsController.ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(NovelTtsController.EXTRA_STATE, state)
            putExtra(NovelTtsController.EXTRA_SESSION_ID, sessionId)
            putExtra(NovelTtsController.EXTRA_ERROR, error)
        })
        if (foreground && updateNotification) getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val paused = state == NovelTtsController.STATE_PAUSED
        val toggleAction = if (paused) NovelTtsController.ACTION_RESUME else NovelTtsController.ACTION_PAUSE
        val toggleIntent = PendingIntent.getService(this, 1, commandIntent(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 2, commandIntent(NovelTtsController.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_play_arrow_24)
            .setContentTitle(title.ifBlank { getString(R.string.reader_tts_title) })
            .setContentText(getString(R.string.reader_tts_progress, (currentIndex + 1).coerceAtMost(segments.size), segments.size))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(if (paused) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_baseline_pause_24,
                getString(if (paused) R.string.reader_tts_resume else R.string.reader_tts_pause), toggleIntent)
            .addAction(R.drawable.spark_ic_stop, getString(R.string.reader_tts_stop), stopIntent)
            .build()
    }

    private fun commandIntent(action: String) = Intent(this, NovelTtsService::class.java)
        .setAction(action).putExtra(NovelTtsController.EXTRA_SESSION_ID, sessionId)

    private fun releaseTts() {
        engineGeneration++
        initialized = false
        tts?.runCatching {
            stop()
            shutdown()
        }
        tts = null
    }

    override fun onDestroy() {
        destroyed = true
        invalidateUtterance()
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(noisyReceiver)
        abandonAudioFocus()
        releaseTts()
        segments = emptyList()
        sourceRange = null
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (state != NovelTtsController.STATE_ERROR) {
            state = NovelTtsController.STATE_IDLE
            publishState()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "novel_tts"
        private const val NOTIFICATION_ID = 0x4E5454
    }
}
