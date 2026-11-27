package ceui.pixiv.ui.novel.reader

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.Settings
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.db.discovery.ProfileManager
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.TranslationRole
import ceui.pixiv.ui.novel.reader.settings.ReaderParagraphSpacingMigrationTest.MemoryMMKV
import ceui.pixiv.ui.translate.appTranslateTargetLang
import com.blankj.utilcode.util.Utils
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
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
class NovelReaderTargetLanguageTest {

    @Before
    fun setUp() {
        MemoryMMKV.values.clear()
    }

    @Test
    fun `changing configured target language invalidates body translation and retranslates in new language`() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        val server = MockWebServer().apply { start() }
        Shaft.sSettings = Settings().apply {
            translateTargetLanguage = "zh-CN"
            isAiTranslateEnabled = true
            aiTranslateBaseUrl = server.url("/v1").toString()
            aiTranslateModel = "model"
            aiTranslatePrompt = ""
            isAiTranslateStreaming = false
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        val store = ViewModelStore()
        val model = NovelReaderV3ViewModel(1401L, DiscoveryPool(app, ProfileManager(app)))
            .also { store.put("reader", it) }
        ReflectionHelpers.setField(model, "tokens", listOf(ContentToken.Paragraph(0, 4, "こんにちは")))
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
            assertEquals("zh-CN", appTranslateTargetLang())
            enqueue("你好")
            model.startBodyTranslation()
            awaitDone()
            assertTrue(model.hasBodyTranslation)
            assertFalse(model.isBodyTranslationStale)
            val firstRequest = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(firstRequest.contains("Simplified Chinese"))

            Shaft.sSettings.translateTargetLanguage = "ko"
            assertEquals("ko", appTranslateTargetLang())
            assertFalse(model.hasBodyTranslation)
            assertTrue(model.isBodyTranslationStale)

            enqueue("안녕하세요")
            val versionBeforeRestart = model.translationVersion.value ?: 0
            model.setTranslationDisplay(TranslationRole.Replacement)
            assertTrue(
                "Restarting with a new language must refresh display immediately to clear stale text",
                (model.translationVersion.value ?: 0) > versionBeforeRestart,
            )
            awaitDone()
            assertTrue(model.hasBodyTranslation)
            assertFalse(model.isBodyTranslationStale)
            assertEquals(2, server.requestCount)
            val secondRequest = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(secondRequest.contains("Korean"))
        } finally {
            store.clear()
            AppDatabase.destroyInstance()
            db.close()
            server.shutdown()
        }
    }

    @Test
    fun `restarting an in-flight translation with a new target language bumps runId and ignores cancelled job failure`() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        val server = MockWebServer().apply { start() }
        Shaft.sSettings = Settings().apply {
            translateTargetLanguage = "zh-CN"
            isAiTranslateEnabled = true
            aiTranslateBaseUrl = server.url("/v1").toString()
            aiTranslateModel = "model"
            aiTranslatePrompt = ""
            isAiTranslateStreaming = false
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        val store = ViewModelStore()
        val model = NovelReaderV3ViewModel(1402L, DiscoveryPool(app, ProfileManager(app)))
            .also { store.put("reader", it) }
        ReflectionHelpers.setField(model, "tokens", listOf(ContentToken.Paragraph(0, 4, "こんにちは")))
        try {
            // 按请求体里的目标语言分派响应，避免取消时序导致新任务误吃旧任务的失败桩
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    return if (body.contains("Korean")) {
                        MockResponse().setBody(
                            """{"choices":[{"message":{"content":${JSONObject.quote("""["안녕하세요"]""")}}}]}""",
                        )
                    } else {
                        MockResponse().setResponseCode(500).setBodyDelay(200, TimeUnit.MILLISECONDS)
                    }
                }
            }
            model.startBodyTranslation()
            val firstRunning = model.translationState.value as NovelReaderV3ViewModel.TranslationState.Running

            Shaft.sSettings.translateTargetLanguage = "ko"
            assertTrue(model.isBodyTranslationStale)
            model.startBodyTranslation()
            val secondRunning = model.translationState.value as NovelReaderV3ViewModel.TranslationState.Running
            assertTrue(secondRunning.runId > firstRunning.runId)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (model.translationState.value is NovelReaderV3ViewModel.TranslationState.Running && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertSame(NovelReaderV3ViewModel.TranslationState.Done, model.translationState.value)
            assertTrue(model.hasBodyTranslation)
            val displayTexts = model.displayTokens().filterIsInstance<ContentToken.Paragraph>().map { it.text }
            assertTrue(displayTexts.contains("안녕하세요"))
        } finally {
            store.clear()
            AppDatabase.destroyInstance()
            db.close()
            server.shutdown()
        }
    }
}
