package ceui.pixiv.ui.common

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.RecyNovelBinding
import ceui.lisa.utils.Settings
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.WebSessionManagerTest.MemoryMMKV
import ceui.pixiv.ui.translate.AiTranslatePhase
import ceui.pixiv.ui.translate.AiTranslator
import ceui.pixiv.ui.translate.CloudTranslator
import ceui.pixiv.utils.Event
import ceui.pixiv.ui.translate.Translator
import ceui.pixiv.ui.translate.appTranslateTargetLang
import com.blankj.utilcode.util.Utils
import kotlinx.coroutines.CompletableDeferred
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class, qualifiers = "w320dp-h640dp",
    shadows = [MemoryMMKV::class], instrumentedPackages = ["com.tencent.mmkv"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelTitleRenderingTest {
    class Host : NovelFeedFragment() {
        override val feedViewModel: FeedViewModel<String> get() = error("Renderer test has no data source")
        fun renderer() = novelCardRenderer()
    }

    class EmptyHost : NovelFeedFragment() {
        override val feedViewModel by feedViewModels<String>(autoLoad = false) {
            FeedSource { FeedPage(emptyList(), null) }
        }
    }

    private fun initApp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
    }

    private fun awaitTranslation(model: NovelTitleTranslationViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.state.value?.running == true && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("Translation did not finish", model.state.value!!.running)
        assertNull(model.state.value?.error)
    }

    @Test fun `changing the configured AI model must retranslate with the new model`() {
        initApp()
        val server = MockWebServer().apply { start() }
        val store = ViewModelStore()
        val model = NovelTitleTranslationViewModel().also { store.put("titles", it) }
        try {
            Shaft.sSettings.apply {
                isAiTranslateEnabled = true
                aiTranslateBaseUrl = server.url("/v1").toString()
                aiTranslateApiKey = "test-key"
                aiTranslateModel = "model-a"
                aiTranslatePrompt = "Translate to English"
                isAiTranslateStreaming = false
            }
            fun reply(text: String) = MockResponse().setBody(
                """{"choices":[{"message":{"content":${JSONObject.quote("[\"$text\"]")}}}]}""",
            )
            server.enqueue(reply("from-model-a"))
            model.translate(listOf("原文"), "en", AiTranslator)
            awaitTranslation(model)
            assertEquals("model-a", JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getString("model"))
            assertEquals("from-model-a", model.title("原文", "en"))
            model.hide()
            Shaft.sSettings.aiTranslateModel = "model-b"
            server.enqueue(reply("from-model-b"))
            model.translate(listOf("原文"), "en", AiTranslator)
            awaitTranslation(model)
            assertEquals("from-model-b", model.title("原文", "en"))
            assertEquals("model-b", JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getString("model"))
        } finally {
            store.clear()
            server.shutdown()
        }
    }

    @Test fun `failure during view recreation is displayed once after reattachment`() {
        initApp()
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()
        val host = EmptyHost()
        val manager = activity.supportFragmentManager
        manager.beginTransaction().add(host, "errors").commitNow()
        val gate = CompletableDeferred<Unit>()
        val engine = object : Translator {
            override suspend fun translate(input: String, outputLang: String, onPhase: ((AiTranslatePhase) -> Unit)?): String {
                gate.await()
                throw IOException("offline")
            }
        }
        val model = ViewModelProvider(host)[NovelTitleTranslationViewModel::class.java]
        try {
            model.translate(listOf("原文"), "en", engine)
            manager.beginTransaction().detach(host).commitNow()
            gate.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(model.state.value?.error)
            manager.beginTransaction().attach(host).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("The failure must reach the new view", dialog)
            assertTrue(dialog.isShowing)
            dialog.dismiss()
            manager.beginTransaction().detach(host).commitNow()
            manager.beginTransaction().attach(host).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("The same failure must not be shown twice", ShadowDialog.getLatestDialog().isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `disabled cloud fallback reports Google network failure instead of a cloud error`() {
        initApp()
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val host = EmptyHost()
        val activity = controller.get()
        activity.supportFragmentManager.beginTransaction().add(host, "fallback").commitNow()
        val model = ViewModelProvider(host)[NovelTitleTranslationViewModel::class.java]
        try {
            // Retain the selected cloud engine, then reproduce its disabled -> Google failure path.
            // No texts means this fixture sends no external request.
            model.translate(emptyList(), "en", CloudTranslator)
            assertFalse(CloudTranslator.isActive())
            val state = ReflectionHelpers.getField<MutableLiveData<NovelTitleTranslationViewModel.State>>(model, "_state")
            state.value = NovelTitleTranslationViewModel.State(error = Event(IOException("Google offline")))
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            val views = arrayListOf<View>()
            dialog.window!!.decorView.findViewsWithText(
                views, activity.getString(R.string.translate_proxy_required_message), View.FIND_VIEWS_WITH_TEXT,
            )
            assertFalse("Google fallback failure must explain the proxy requirement", views.isEmpty())
            dialog.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `translated title survives holder reuse and never reveals a muted card`() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        val wasLoaded = ReflectionHelpers.getField<Boolean>(NovelMuteStore, "loaded")
        ReflectionHelpers.setField(NovelMuteStore, "loaded", true)
        val muted = ReflectionHelpers.getField<MutableSet<Long>>(NovelMuteStore, "mutedIds")
        val novel = Novel(id = 1372, title = "日本語のタイトル", visible = true)
        val hadMute = novel.id in muted
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()
        val host = Host()
        activity.supportFragmentManager.beginTransaction().add(host, "titles")
            .setMaxLifecycle(host, Lifecycle.State.CREATED).commitNow()
        val adapter = FeedAdapter(listOf(host.renderer()))
        adapter.submitList(listOf(NovelFeedItem(novel)))
        val parent = FrameLayout(activity)
        val cell = adapter.createViewHolder(parent, 0)
        val binding = cell.binding as RecyNovelBinding
        val model = ViewModelProvider(host)[NovelTitleTranslationViewModel::class.java]
        val lang = appTranslateTargetLang()
        val translated = "译文 translated title ".repeat(12)
        val engine = object : Translator {
            override suspend fun translate(input: String, outputLang: String, onPhase: ((AiTranslatePhase) -> Unit)?) = translated
        }
        val payload = ReflectionHelpers.getStaticField<Any>(
            Class.forName("ceui.pixiv.ui.common.NovelFeedFragmentKt"), "PAYLOAD_NOVEL_TITLE",
        )
        try {
            adapter.onBindViewHolder(cell, 0)
            assertEquals(novel.title, binding.title.text.toString())
            model.translate(listOf(novel.title!!), lang, engine)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(model.state.value?.error)

            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 2f
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                adapter.onBindViewHolder(cell, 0, mutableListOf(payload))
                assertEquals(translated, binding.title.text.toString())
                adapter.onBindViewHolder(cell, 0) // A recycled holder reads the same display state.
                assertEquals(translated, binding.title.text.toString())
                assertEquals("日本語のタイトル", novel.title)
            }

            muted += novel.id
            adapter.onBindViewHolder(cell, 0)
            assertEquals(" ", binding.title.text.toString())
            adapter.onBindViewHolder(cell, 0, mutableListOf(payload))
            assertEquals(" ", binding.title.text.toString())
            muted -= novel.id
            model.hide()
            adapter.onBindViewHolder(cell, 0)
            assertEquals(novel.title, binding.title.text.toString())
        } finally {
            if (hadMute) muted += novel.id else muted -= novel.id
            ReflectionHelpers.setField(NovelMuteStore, "loaded", wasLoaded)
            controller.pause().stop().destroy()
        }
    }
}
