package ceui.pixiv.chat.api

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.IncomingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Short haptic tick on every inbound DM [ChatFrame.Msg]. App-scoped,
 * subscribes to [ShaftChatGateway.incoming] — mirrors the lifecycle and
 * shape of [ceui.pixiv.banner.host.ChatBannerBridge].
 *
 * ## Suppression rules
 *
 * - **room == "global"** — public chatroom traffic; vibrating on every msg
 *   would make the device unusable during busy hours. DM is the only
 *   "personal ping" surface in the protocol today.
 * - **uid == selfUid** — server echoes the user's own broadcast back as the
 *   ACK (doc §4.3). Vibrating "you sent a message" is useless.
 * - **cooldown not elapsed** — 5 messages in 1s (per-conn send bucket cap)
 *   without throttling = 5 buzzes that *feel* like one long unpleasant
 *   vibration. 500 ms collapses bursts into a single tick while still
 *   firing for replies seconds later.
 * - **silent ringer / DND** — convention: SILENT mode and
 *   `INTERRUPTION_FILTER_NONE` / `_ALARMS` suppress all haptic alerts.
 *   The `USAGE_NOTIFICATION` audio-attrs hint lets the OS also apply
 *   per-channel rules where supported.
 *
 * ## Effect choice (descending precedence)
 *
 * - **API 29+ (Q+)**: `VibrationEffect.EFFECT_TICK` — OEM-calibrated
 *   low-intensity confirmation preset. Consistent feel across devices,
 *   no magic numbers in code.
 * - **API 26-28 (O - P)**: `createOneShot(20ms, DEFAULT_AMPLITUDE)` — short
 *   snap with hardware-default amplitude.
 * - **API 24-25 (N)**: deprecated `vibrate(long)` — only path that exists.
 *
 * ## In-conversation buzzing is intentional
 *
 * [ceui.pixiv.banner.host.ChatBannerBridge] suppresses banners when the user is
 * already viewing the source room (overlay would obscure the same content
 * they want to see). Haptic does NOT do that — vibration is a non-visual
 * affordance that doesn't obstruct anything, and "feedback that a new
 * message just arrived in this thread" is genuinely useful (Telegram /
 * WhatsApp / iMessage all do it).
 */
class ChatHapticBridge(
    app: Application,
    private val incoming: Flow<IncomingMessage>,
    private val scope: CoroutineScope,
) {

    private val vibrator: Vibrator? = obtainVibrator(app)
    private val audio: AudioManager? =
        app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notif: NotificationManager? =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    @Volatile private var lastBuzzMs = 0L
    private var job: Job? = null

    fun start() {
        if (job != null) return
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            Timber.tag(TAG).i("no vibrator hardware — bridge no-op")
            return
        }
        job = scope.launch {
            incoming
                .filterIsInstance<IncomingMessage.Text>()
                .map { ChatFrameDecoder.decode(it.text) }
                .filterIsInstance<ChatFrame.Msg>()
                .collect { msg ->
                    if (tryAcquireBuzz(msg)) fire(v)
                }
        }
        Timber.tag(TAG).i("ChatHapticBridge started")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Check + claim. Returns true if this frame is eligible to vibrate AND
     * "claims" the cooldown slot ([lastBuzzMs] = now); returns false (no
     * mutation) when the frame should be ignored.
     *
     * Why claim *before* [fire] rather than after: if [fire] throws (rare
     * OEM-side `SecurityException`), we deliberately do NOT retry the next
     * frame within the cooldown window. Treating a hardware failure as
     * "consumed the buzz slot" prevents flapping fire() against a broken
     * Vibrator at full WS frame rate. The cost is one missed buzz on the
     * very next msg — acceptable since vibrate failures are themselves rare.
     */
    private fun tryAcquireBuzz(msg: ChatFrame.Msg): Boolean {
        if (msg.room == ChatThreadId.ROOM_GLOBAL) return false
        val selfUid = SessionManager.loggedInUid
        if (selfUid != 0L && msg.uid == selfUid) return false
        if (audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        if (isDndSuppressing()) return false
        val now = System.currentTimeMillis()
        if (now - lastBuzzMs < COOLDOWN_MS) return false
        lastBuzzMs = now
        return true
    }

    /**
     * DND check: NONE = total silence, ALARMS = "alarms only" (no msg-style
     * vibration). PRIORITY is grey area — we let it through, matching what
     * a notification with `USAGE_NOTIFICATION` would do by default. ALL is
     * normal operation.
     *
     * Falls back to "don't suppress" if the service is unavailable or the
     * query throws (rare OEM-side bug paths).
     */
    private fun isDndSuppressing(): Boolean {
        val m = notif ?: return false
        return try {
            val f = m.currentInterruptionFilter
            f == NotificationManager.INTERRUPTION_FILTER_NONE
                || f == NotificationManager.INTERRUPTION_FILTER_ALARMS
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "currentInterruptionFilter threw")
            false
        }
    }

    private fun fire(v: Vibrator) {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> vibrateWithAttrs(
                    v, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> vibrateWithAttrs(
                    v, VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE),
                )
                else -> {
                    @Suppress("DEPRECATION")
                    v.vibrate(TICK_MS)
                }
            }
        } catch (t: Throwable) {
            // OEM-specific Vibrator bugs (e.g. SecurityException on certain
            // OPPO/Vivo builds) shouldn't bubble out of an app-scoped
            // collector. One buzz failing isn't worth crashing the bridge.
            Timber.tag(TAG).w(t, "vibration call threw")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun vibrateWithAttrs(v: Vibrator, effect: VibrationEffect) {
        // USAGE_NOTIFICATION + CONTENT_TYPE_SONIFICATION is the recommended
        // pairing for "system has something for the user, treat as a
        // notification-style ping for stream-routing and DND rules" — same
        // attrs a Notification builder would use for its vibrate pattern.
        // VibrationAttributes (API 33+) is the modern equivalent but takes
        // AudioAttributes interop for free, so the AudioAttributes route
        // is the lowest-friction "works correctly on 26-36" choice.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        v.vibrate(effect, attrs)
    }

    companion object {
        private const val TAG = "Chat-Haptic"
        /** Burst-collapse window — see class kdoc. */
        private const val COOLDOWN_MS = 500L
        /** Hand-tuned short snap for pre-Q fallbacks; EFFECT_TICK is shorter than this on most devices. */
        private const val TICK_MS = 20L

        /**
         * API 31+ exposes `VibratorManager.defaultVibrator` as the new path;
         * 26-30 use the legacy `VIBRATOR_SERVICE`. Bundled in a try/catch
         * because some OEM builds throw on getSystemService for missing
         * hardware instead of returning null.
         */
        private fun obtainVibrator(ctx: Context): Vibrator? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "obtainVibrator threw")
            null
        }
    }
}
