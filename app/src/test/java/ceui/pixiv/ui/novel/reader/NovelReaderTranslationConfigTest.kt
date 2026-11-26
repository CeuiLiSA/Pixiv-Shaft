package ceui.pixiv.ui.novel.reader

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.Settings
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.db.discovery.ProfileManager
import ceui.pixiv.session.WebSessionManagerTest.MemoryMMKV
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.translate.AiTranslator
import ceui.pixiv.ui.translate.GoogleWebTranslator
import com.blankj.utilcode.util.Utils
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class,
    shadows = [MemoryMMKV::class], instrumentedPackages = ["com.tencent.mmkv"])
class NovelReaderTranslationConfigTest {
    @Test fun `new AI prompt invalidates body translations instead of reporting stale text as done`() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        val server = MockWebServer().apply { start() }
        Shaft.sSettings = Settings().apply {
            isAiTranslateEnabled = true
            aiTranslateBaseUrl = server.url("/v1").toString()
            aiTranslateModel = "model"
            aiTranslatePrompt = "First prompt"
            isAiTranslateStreaming = false
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        val store = ViewModelStore()
        val model = NovelReaderV3ViewModel(1373L, DiscoveryPool(app, ProfileManager(app)))
            .also { store.put("reader", it) }
        ReflectionHelpers.setField(model, "tokens", listOf(ContentToken.Paragraph(0, 2, "原文")))
        fun enqueue(text: String) {
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"content":${JSONObject.quote("[\"$text\"]")}}}]}""",
            ))
        }
        fun awaitDone() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (model.translationState.value is NovelReaderV3ViewModel.TranslationState.Running && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertSame(NovelReaderV3ViewModel.TranslationState.Done, model.translationState.value)
        }
        try {
            enqueue("first translation")
            model.startBodyTranslation()
            awaitDone()
            assertTrue(model.hasBodyTranslation)
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            model.hideBodyTranslation()
            Shaft.sSettings.aiTranslatePrompt = "Second prompt"
            enqueue("second translation")
            model.startBodyTranslation()
            awaitDone()
            assertTrue(model.hasBodyTranslation)
            assertEquals(2, server.requestCount)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(request.body.readUtf8().contains("Second prompt"))
        } finally {
            store.clear()
            AppDatabase.destroyInstance()
            db.close()
            server.shutdown()
        }
    }

    @Test fun `started translation state reports active AI model name for toast`() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        val server = MockWebServer().apply { start() }
        Shaft.sSettings = Settings().apply {
            isAiTranslateEnabled = true
            aiTranslateBaseUrl = server.url("/v1").toString()
            aiTranslateModel = " deepseek-chat "
            isAiTranslateStreaming = false
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        val store = ViewModelStore()
        val model = NovelReaderV3ViewModel(1397L, DiscoveryPool(app, ProfileManager(app)))
            .also { store.put("reader", it) }
        ReflectionHelpers.setField(model, "tokens", listOf(ContentToken.Paragraph(0, 2, "原文")))
        val observed = mutableListOf<NovelReaderV3ViewModel.TranslationState>()
        val observer: (NovelReaderV3ViewModel.TranslationState) -> Unit = { observed += it }
        model.translationState.observeForever(observer)
        try {
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"content":${JSONObject.quote("""["译文"]""")}}}]}""",
            ))
            model.startBodyTranslation()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (model.translationState.value is NovelReaderV3ViewModel.TranslationState.Running && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val running = observed.filterIsInstance<NovelReaderV3ViewModel.TranslationState.Running>().first()
            assertSame(AiTranslator, running.translator)
            assertEquals("deepseek-chat", running.modelName)
            val toast = readerTranslationStartedText(app, running.translator, running.modelName)
            assertEquals(app.getString(R.string.msg_reader_translate_started_model, "deepseek-chat"), toast)
            assertNotEquals(app.getString(R.string.msg_reader_translate_started), toast)
        } finally {
            model.translationState.removeObserver(observer)
            store.clear()
            AppDatabase.destroyInstance()
            db.close()
            server.shutdown()
        }
    }

    @Test fun `disabled AI reports Google channel and blank AI model never claims Google`() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sSettings = Settings().apply {
            isAiTranslateEnabled = false
            aiTranslateBaseUrl = "https://api.example.com/v1"
            aiTranslateModel = "deepseek-chat"
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        val store = ViewModelStore()
        val model = NovelReaderV3ViewModel(13970L, DiscoveryPool(app, ProfileManager(app)))
            .also { store.put("reader", it) }
        ReflectionHelpers.setField(model, "tokens", listOf(ContentToken.Paragraph(0, 2, "原文")))
        try {
            model.startBodyTranslation()
            val running = model.translationState.value as NovelReaderV3ViewModel.TranslationState.Running
            model.hideBodyTranslation()
            assertSame(GoogleWebTranslator, running.translator)
            assertNull(running.modelName)
            assertEquals(
                app.getString(R.string.msg_reader_translate_started),
                readerTranslationStartedText(app, running.translator, running.modelName),
            )
            assertEquals(
                app.getString(R.string.msg_reader_translate_started_model, "AI"),
                readerTranslationStartedText(app, AiTranslator, "   "),
            )
        } finally {
            store.clear()
            AppDatabase.destroyInstance()
            db.close()
        }
    }
}
