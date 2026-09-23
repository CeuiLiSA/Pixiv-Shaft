package ceui.pixiv.ui.novel.reader.tts

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class NovelTtsProgressTest {
    @Before fun grantManifestPermission() {
        val app = RuntimeEnvironment.getApplication()
        // On API 24 AndroidX uses this manifest-merged signature permission;
        // Robolectric does not automatically grant it as an installed APK does.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @Test fun `utterance fallback and resumed engine ranges publish reader offsets`() {
        val controller = Robolectric.buildService(NovelTtsService::class.java).create()
        try {
            val service = controller.get()
            prepare(service, "first", offset = 3)
            val listener = ReflectionHelpers.getField<UtteranceProgressListener>(service, "listener")
            listener.onStart("first")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(103..109, NovelTtsController.playbackState.sourceRange)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                listener.onRangeStart("first", 1, 4, 0)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(104..106, NovelTtsController.playbackState.sourceRange)
            }
        } finally { controller.destroy() }
        assertNull(NovelTtsController.playbackState.sourceRange)
        assertEquals(NovelTtsController.STATE_IDLE, NovelTtsController.playbackState.state)
    }

    @Test fun `callbacks queued before a seek or pause cannot move the highlight`() {
        val controller = Robolectric.buildService(NovelTtsService::class.java).create()
        try {
            val service = controller.get()
            prepare(service, "old")
            val listener = ReflectionHelpers.getField<UtteranceProgressListener>(service, "listener")
            listener.onStart("old")
            // The callback is queued on Main. A new utterance invalidates it before delivery.
            prepare(service, "new", offset = 5)
            listener.onStart("new")
            listener.onDone("old")
            listener.onError("old")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(105..109, NovelTtsController.playbackState.sourceRange)
            assertEquals(NovelTtsController.STATE_PLAYING, NovelTtsController.playbackState.state)

            service.onStartCommand(Intent().apply {
                action = NovelTtsController.ACTION_PAUSE
                putExtra(NovelTtsController.EXTRA_SESSION_ID, "novel:1")
            }, 0, 1)
            listener.onDone("new")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(NovelTtsController.STATE_PAUSED, NovelTtsController.playbackState.state)
            assertEquals(105..109, NovelTtsController.playbackState.sourceRange)
            assertEquals(NovelTtsController.STATE_IDLE, NovelTtsController.playbackState.forSession("novel:2"))
        } finally { controller.destroy() }
    }

    private fun prepare(service: NovelTtsService, utterance: String, offset: Int = 0) {
        ReflectionHelpers.setField(service, "segments", listOf(NovelTtsText.Segment("abcdefghij", 100)))
        ReflectionHelpers.setField(service, "sessionId", "novel:1")
        ReflectionHelpers.setField(service, "state", NovelTtsController.STATE_PLAYING)
        ReflectionHelpers.setField(service, "activeUtterance", utterance)
        ReflectionHelpers.setField(service, "utteranceOffset", offset)
        ReflectionHelpers.setField(service, "currentOffset", offset)
    }
}
