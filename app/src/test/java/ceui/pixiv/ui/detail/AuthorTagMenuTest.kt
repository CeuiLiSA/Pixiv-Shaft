package ceui.pixiv.ui.detail

import android.app.Dialog
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.descendants
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.CellNovelTagsBinding
import ceui.lisa.databinding.FragmentIllustBinding
import ceui.lisa.databinding.SectionV3TagsBinding
import ceui.lisa.fragments.FragmentIllust
import ceui.lisa.utils.Params
import ceui.lisa.utils.Settings
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.loxia.Novel
import ceui.pixiv.api.API
import ceui.pixiv.api.Client
import ceui.pixiv.api.PixivWebApi
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.snapshot.SnapshotManagerFragment
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.novel.NovelTagsFeedItem
import ceui.pixiv.ui.novel.novelTagsRenderer
import ceui.pixiv.widgets.V3TagFlowView
import com.blankj.utilcode.util.Utils
import java.lang.reflect.Proxy
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/** Use the real detail renderer and menu; fail on any app/web API call before navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class AuthorTagMenuTest {
    private lateinit var host: FragmentActivity
    private lateinit var fragment: ArtworkV3Fragment
    private lateinit var db: AppDatabase
    private var oldAppApi: API? = null
    private var oldWebApi: PixivWebApi? = null
    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        oldAppApi = ReflectionHelpers.getField(Client, "_appApi")
        oldWebApi = ReflectionHelpers.getField(Client, "_webApi")
        ReflectionHelpers.setField(Client, "_appApi", rejectRequests(API::class.java))
        ReflectionHelpers.setField(Client, "_webApi", rejectRequests(PixivWebApi::class.java))
        host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        host.setTheme(R.style.AppTheme)
        fragment = ArtworkV3Fragment().apply { arguments = Bundle() }
        // Attach for navigation/resources, but render only the tag section under test.
        host.supportFragmentManager.beginTransaction()
            .add(fragment, "detail")
            .setMaxLifecycle(fragment, Lifecycle.State.CREATED)
            .commitNow()
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        ReflectionHelpers.setField(Client, "_appApi", oldAppApi)
        ReflectionHelpers.setField(Client, "_webApi", oldWebApi)
        AppDatabase.destroyInstance()
        db.close()
    }

    @Test
    fun `binding rebinding opening and dismissing menus make zero requests`() {
        val adapter = FeedAdapter(listOf(fragment.tagsRenderer()))
        adapter.submitList(listOf(ArtworkTagsItem(work("illust"))))
        val cell = adapter.createViewHolder(FrameLayout(host), 0)
        repeat(3) {
            adapter.bindViewHolder(cell, 0)
            val flow = (cell.binding as SectionV3TagsBinding).tagsFlow
            val dialog = openMenu(flow)
            assertNotNull(authorAction(dialog))
            assertNull(shadowOf(host).nextStartedActivity)
            dialog.dismiss()
        }
        idle()
        assertTrue(requests.isEmpty())
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test
    fun `only selecting the author action navigates with original tag long uid and correct category`() {
        for ((type, route) in listOf(
            "illust" to TemplateRoute.USER_ILLUSTS_BY_TAG,
            "ugoira" to TemplateRoute.USER_ILLUSTS_BY_TAG,
            "manga" to TemplateRoute.USER_MANGA_BY_TAG,
        )) {
            val dialog = openMenu(render(work(type)))
            assertNull(shadowOf(host).nextStartedActivity)
            assertTrue(authorAction(dialog)!!.performClick())
            val intent = shadowOf(host).nextStartedActivity
            assertEquals(TemplateActivity::class.java.name, intent.component!!.className)
            assertEquals(3_000_000_001L, intent.getLongExtra(Params.USER_ID, 0L))
            assertEquals("初音 ミク", intent.getStringExtra(Params.KEY_WORD))
            assertEquals(route.key, intent.getStringExtra(TemplateActivity.EXTRA_FRAGMENT))
            assertFalse(dialog.isShowing)
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `missing authors and non-detail tag rows do not offer author search`() {
        for (user in listOf(null, User(id = 0L))) {
            val dialog = openMenu(render(work("illust").copy(user = user)))
            assertNull(authorAction(dialog))
            dialog.dismiss()
        }
        val flow = V3TagFlowView(host).apply { setTagNames(listOf("初音 ミク")) }
        assertNull(authorAction(openMenu(flow)))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `snapshot tags preserve their copy-only long press`() {
        fragment.requireArguments().putString(SnapshotManagerFragment.ARG_SNAPSHOT_ID, "local")
        val flow = render(work("illust"))
        assertNull(flow.onViewAuthorWorks)
        assertNotNull(flow.onTagLongClick)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `legacy detail menu also defers navigation and uses the current work type`() {
        val legacy = FragmentIllust().apply { arguments = Bundle() }
        host.supportFragmentManager.beginTransaction().add(legacy, "legacy")
            .setMaxLifecycle(legacy, Lifecycle.State.CREATED).commitNow()
        val binding = FragmentIllustBinding.inflate(LayoutInflater.from(host))
        ReflectionHelpers.setField(legacy, "baseBind", binding)
        for ((type, route) in listOf(
            "illust" to TemplateRoute.USER_ILLUSTS_BY_TAG,
            "manga" to TemplateRoute.USER_MANGA_BY_TAG,
        )) {
            ReflectionHelpers.callInstanceMethod<Void>(legacy, "setupTags",
                ReflectionHelpers.ClassParameter.from(Illust::class.java, work(type)))
            assertTrue(binding.illustTag.getChildAt(0).performLongClick())
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(requests.isEmpty())
            assertNull(shadowOf(host).nextStartedActivity)
            assertTrue(authorAction(dialog)!!.performClick())
            val intent = shadowOf(host).nextStartedActivity
            assertEquals(route.key, intent.getStringExtra(TemplateActivity.EXTRA_FRAGMENT))
            assertEquals(3_000_000_001L, intent.getLongExtra(Params.USER_ID, 0L))
            assertEquals("初音 ミク", intent.getStringExtra(Params.KEY_WORD))
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `novel menu reads the current pooled author without fetching detail`() {
        val novel = Novel(id = 1102L, tags = work("illust").tags)
        val adapter = FeedAdapter(listOf(novelTagsRenderer(host)))
        adapter.submitList(listOf(NovelTagsFeedItem(novel.id)))
        val cell = adapter.createViewHolder(FrameLayout(host), 0)
        adapter.bindViewHolder(cell, 0)
        for (userId in listOf(3_000_000_001L, 3_000_000_002L)) {
            ObjectPool.update(novel.copy(user = User(id = userId)))
            val dialog = openMenu((cell.binding as CellNovelTagsBinding).tagsFlow)
            assertNull(shadowOf(host).nextStartedActivity)
            assertTrue(authorAction(dialog)!!.performClick())
            val intent = shadowOf(host).nextStartedActivity
            assertEquals(TemplateRoute.USER_NOVELS_BY_TAG.key,
                intent.getStringExtra(TemplateActivity.EXTRA_FRAGMENT))
            assertEquals(userId, intent.getLongExtra(Params.USER_ID, 0L))
            assertEquals("初音 ミク", intent.getStringExtra(Params.KEY_WORD))
        }
        adapter.onViewRecycled(cell)
        assertTrue(requests.isEmpty())
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `author menu label fits narrow screens in both themes with larger fonts and long tags`() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            for (scale in listOf(1f, 1.3f)) {
                for (language in listOf("zh", "en", "ja", "ko", "ru", "tr", "zh-TW")) {
                    val config = Configuration(host.resources.configuration).apply {
                        uiMode = Configuration.UI_MODE_TYPE_NORMAL or night
                        fontScale = scale
                        setLocale(Locale.forLanguageTag(language))
                    }
                    val context = ContextThemeWrapper(host, R.style.AppTheme).apply {
                        applyOverrideConfiguration(config)
                    }
                    val flow = V3TagFlowView(context).apply {
                        onViewAuthorWorks = {}
                        setTagNames(listOf("初音ミクと巡音ルカの長いタグ ".repeat(3)))
                    }
                    val dialog = openMenu(flow)
                    val text = (dialog.window!!.decorView as ViewGroup).descendants
                        .filterIsInstance<TextView>().first {
                            it.text == context.getString(R.string.tag_menu_author_works)
                        }
                    val row = text.parent as ViewGroup
                    assertTrue("$language, night=$night, scale=$scale: row outside menu",
                        row.left >= 0 && row.right <= (row.parent as View).width)
                    assertTrue("$language, night=$night, scale=$scale: text clipped",
                        text.layout.height <= text.height - text.paddingTop - text.paddingBottom)
                    assertTrue(row.performClick())
                    assertFalse(dialog.isShowing)
                }
            }
        }
        assertTrue(requests.isEmpty())
    }

    private fun render(work: Illust): V3TagFlowView {
        val adapter = FeedAdapter(listOf(fragment.tagsRenderer()))
        adapter.submitList(listOf(ArtworkTagsItem(work)))
        val cell = adapter.createViewHolder(FrameLayout(host), 0)
        adapter.bindViewHolder(cell, 0)
        return (cell.binding as SectionV3TagsBinding).tagsFlow
    }

    private fun openMenu(flow: V3TagFlowView): Dialog {
        assertTrue(flow.getChildAt(0).performLongClick())
        idle()
        assertTrue(requests.isEmpty())
        return ShadowDialog.getLatestDialog()
    }

    private fun authorAction(dialog: Dialog): View? =
        (dialog.window!!.decorView as ViewGroup).descendants
            .filterIsInstance<TextView>()
            .firstOrNull { it.text == host.getString(R.string.tag_menu_author_works) }
            ?.let { if (it.isClickable) it else it.parent as? View }

    private fun work(type: String) = Illust(
        id = 1102L, type = type, user = User(id = 3_000_000_001L),
        tags = listOf(Tag(name = "初音 ミク", translated_name = "Hatsune Miku")),
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T : Any> rejectRequests(api: Class<T>): T = requireNotNull(api.cast(
        Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { _, method, _ ->
            requests += method.name
            error("Unexpected API request from detail tags: ${method.name}")
        }
    ))
}
