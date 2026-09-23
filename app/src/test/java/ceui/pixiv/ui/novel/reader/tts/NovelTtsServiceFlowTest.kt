package ceui.pixiv.ui.novel.reader.tts

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class, shadows = [NovelTtsServiceFlowTest.ManualTts::class])
class NovelTtsServiceFlowTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ServiceController<NovelTtsService>
    private var startId = 0

    @Before fun setUp() {
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        controller = Robolectric.buildService(NovelTtsService::class.java).create()
    }

    @After fun tearDown() { controller.destroy() }

    @Test fun `real commands preserve resume offsets across speed changes and finish the queue`() {
        val engine = start("novel:1", listOf(NovelTtsText.Segment("abcdef", 100), NovelTtsText.Segment("ghij", 200)))
        initialize(engine)
        val first = engine.requests.last()
        assertEquals("abcdef", first.text)
        engine.utteranceProgressListener.onStart(first.id)
        idle()
        assertEquals(100..105, NovelTtsController.playbackState.sourceRange)
        val offset = if (Build.VERSION.SDK_INT >= 26) 2 else 0
        if (Build.VERSION.SDK_INT >= 26) {
            engine.utteranceProgressListener.onRangeStart(first.id, 2, 4, 0)
            idle()
            assertEquals(102..103, NovelTtsController.playbackState.sourceRange)
        }
        command { NovelTtsController.pause(app, "novel:1") }
        engine.utteranceProgressListener.onDone(first.id)
        idle()
        assertEquals(NovelTtsController.STATE_PAUSED, NovelTtsController.playbackState.state)
        command { NovelTtsController.resume(app, "novel:1") }
        val resumed = engine.requests.last()
        assertNotEquals(first.id, resumed.id)
        assertEquals("abcdef".substring(offset), resumed.text)
        engine.utteranceProgressListener.onStart(resumed.id)
        idle()
        assertEquals((100 + offset)..105, NovelTtsController.playbackState.sourceRange)

        command { NovelTtsController.setSpeed(app, "novel:1", 1.5f) }
        val spedUp = engine.requests.last()
        assertEquals(1.5f, engine.rate, 0f)
        assertEquals(resumed.text, spedUp.text)
        engine.utteranceProgressListener.onDone(resumed.id)
        idle()
        assertEquals(spedUp, engine.requests.last())
        engine.utteranceProgressListener.onDone(spedUp.id)
        idle()
        val next = engine.requests.last()
        assertEquals("ghij", next.text)
        engine.utteranceProgressListener.onStart(next.id)
        idle()
        assertEquals(200..203, NovelTtsController.playbackState.sourceRange)
        engine.utteranceProgressListener.onDone(next.id)
        idle()
        assertEquals(NovelTtsController.STATE_IDLE, NovelTtsController.playbackState.state)
        assertNull(NovelTtsController.playbackState.sourceRange)
        assertTrue(engine.isShutdown)
    }

    @Test fun `switching novels ignores late engine initialization and old playback commands`() {
        val old = start("novel:1", listOf(NovelTtsText.Segment("old", 100)))
        val current = start("novel:2", listOf(NovelTtsText.Segment("new", 300)))
        initialize(old)
        assertTrue(old.requests.isEmpty())
        assertTrue(old.isShutdown)
        initialize(current)
        val request = current.requests.single()
        current.utteranceProgressListener.onStart(request.id)
        idle()
        assertEquals("novel:2", NovelTtsController.playbackState.sessionId)
        assertEquals(300..302, NovelTtsController.playbackState.sourceRange)
        command { NovelTtsController.stop(app, "novel:1") }
        assertEquals(NovelTtsController.STATE_PLAYING, NovelTtsController.playbackState.state)
        command { NovelTtsController.stop(app, "novel:2") }
        current.utteranceProgressListener.onStart(request.id)
        current.utteranceProgressListener.onError(request.id, TextToSpeech.ERROR_SYNTHESIS)
        idle()
        assertEquals(NovelTtsController.STATE_IDLE, NovelTtsController.playbackState.state)
        assertNull(NovelTtsController.playbackState.sourceRange)
    }

    @Test fun `audio focus loss pauses and engine errors clear the active highlight`() {
        val engine = start("novel:1", listOf(NovelTtsText.Segment("abcdef", 100)))
        initialize(engine)
        engine.utteranceProgressListener.onStart(engine.requests.last().id)
        idle()
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        audio.lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        assertEquals(NovelTtsController.STATE_PAUSED, NovelTtsController.playbackState.state)
        assertEquals(100..105, NovelTtsController.playbackState.sourceRange)
        command { NovelTtsController.resume(app, "novel:1") }
        engine.utteranceProgressListener.onError(engine.requests.last().id, TextToSpeech.ERROR_SYNTHESIS)
        idle()
        assertEquals(NovelTtsController.STATE_ERROR, NovelTtsController.playbackState.state)
        assertNull(NovelTtsController.playbackState.sourceRange)
        assertTrue(engine.isShutdown)
    }

    private fun start(session: String, segments: List<NovelTtsText.Segment>): ManualTts {
        command { NovelTtsController.start(app, session, "Title", segments, 1f, 1f, null, null, "en-US") }
        return Shadow.extract(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    private fun initialize(engine: ManualTts) {
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
    }

    private fun command(send: () -> Unit) {
        send()
        val intent: Intent = requireNotNull(shadowOf(app).nextStartedService)
        controller.get().onStartCommand(intent, 0, ++startId)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Exercise the real service; only replace the external engine's timing. */
    @Implements(TextToSpeech::class)
    class ManualTts : ShadowTextToSpeech() {
        data class Request(val text: String, val id: String)
        val requests = mutableListOf<Request>()
        var rate = 1f

        @Implementation public override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            requests += Request(text.toString(), requireNotNull(utteranceId))
            return TextToSpeech.SUCCESS
        }

        @Implementation fun setSpeechRate(value: Float): Int {
            rate = value
            return TextToSpeech.SUCCESS
        }
    }
}
