package ceui.pixiv.ui.common

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.RecyNovelBinding
import ceui.lisa.utils.Settings
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.session.WebSessionManagerTest.MemoryMMKV
import ceui.pixiv.ui.translate.AiTranslatePhase
import ceui.pixiv.ui.translate.Translator
import ceui.pixiv.ui.translate.appTranslateTargetLang
import com.blankj.utilcode.util.Utils
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class, qualifiers = "w320dp-h640dp",
    shadows = [MemoryMMKV::class], instrumentedPackages = ["com.tencent.mmkv"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelTitleRenderingTest {
    class Host : NovelFeedFragment() {
        override val feedViewModel: FeedViewModel<String> get() = error("Renderer test has no data source")
        fun renderer() = novelCardRenderer()
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
