package ceui.lisa.utils

import android.content.Context
import android.content.SharedPreferences
import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.settings.BookmarkSurface
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「作品卡片上显示收藏按钮」的**旧升新 / 降级**：走真实的装载 / 落盘入口
 * （`Local.getSettings` / `Local.setSettings`），而不是直接调 `migrateLegacyBookmarkSurfaces`
 * ——后者证明不了迁移真的接在那两处。
 *
 * 旧版只有两个「不显示」开关（hideStarButtonAtMyCollection / widgetHideBookmarkButton），新版是
 * 「隐藏了哪些卡面」的集合。取反方向搞错、或迁移反复回灌，都会把老用户的选择吃掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = BookmarkSurfacesUpgradeTest.TestApplication::class)
@Suppress("DEPRECATION")
class BookmarkSurfacesUpgradeTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        prefs = app.getSharedPreferences(Local.LOCAL_DATA, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // 真实的 Shaft.onCreate 干的就是这几件事，测试里手工补上。
        Shaft.sGson = Gson()
        Shaft.sPreferences = prefs
        Shaft.sSettings = Settings()
    }

    // ── 旧升新（取反方向）──────────────────────────────────────────

    @Test
    fun `旧收藏页开关开着升级后收藏页仍不显示收藏按钮`() {
        seed("""{"hideStarButtonAtMyCollection":true}""")
        assertFalse(
            Local.getSettings().isBookmarkSurfaceVisible(BookmarkSurface.MY_COLLECTION),
        )
    }

    @Test
    fun `旧小组件开关开着升级后小组件仍不显示收藏按钮`() {
        seed("""{"widgetHideBookmarkButton":true}""")
        assertFalse(
            Local.getSettings().isBookmarkSurfaceVisible(BookmarkSurface.WIDGET),
        )
    }

    @Test
    fun `旧开关都没开时全部卡面都显示`() {
        seed("""{"hideStarButtonAtMyCollection":false,"widgetHideBookmarkButton":false}""")
        assertTrue(allVisible(Local.getSettings()))
    }

    @Test
    fun `没有任何设置文件时全部卡面都显示`() {
        assertTrue(allVisible(Local.getSettings()))
    }

    @Test
    fun `升级会落盘新字段，不必每次重新推导`() {
        seed("""{"hideStarButtonAtMyCollection":true}""")
        Local.setSettings(Local.getSettings())
        val stored = rawSettings()
        val hidden = stored.getAsJsonArray("hiddenBookmarkSurfaces").map { it.asString }
        assertTrue("hiddenBookmarkSurfaces 没落盘", hidden.contains("my_collection"))
        assertTrue("迁移标记没落盘", stored.get("bookmarkSurfacesMigrated").asBoolean)
        // 再读一次仍稳定
        assertFalse(
            Local.getSettings().isBookmarkSurfaceVisible(BookmarkSurface.MY_COLLECTION),
        )
    }

    // ── 迁移只跑一次：新版里重新打开后不会被旧值种回来 ────────────────

    @Test
    fun `迁移过一次后把收藏按钮全开，不会被旧字段再种回来`() {
        seed("""{"hideStarButtonAtMyCollection":true}""")
        assertFalse(
            Local.getSettings().isBookmarkSurfaceVisible(BookmarkSurface.MY_COLLECTION),
        )
        // 用户在新版里把「收藏页」重新打开
        val settings = Local.getSettings()
        settings.setBookmarkSurfaceVisible(BookmarkSurface.MY_COLLECTION, true)
        Local.setSettings(settings)
        assertTrue(
            "迁移标记没挡住回灌，用户重新打开的选择被旧字段吃掉了",
            Local.getSettings().isBookmarkSurfaceVisible(BookmarkSurface.MY_COLLECTION),
        )
    }

    // ── 降级：旧版只认两个卡面，回填不能漏 ───────────────────────────

    @Test
    fun `关掉收藏页会回填旧收藏页开关`() {
        Local.setSettings(Settings().apply {
            setBookmarkSurfaceVisible(BookmarkSurface.MY_COLLECTION, false)
        })
        assertTrue(boolField("hideStarButtonAtMyCollection"))
    }

    @Test
    fun `关掉小组件会回填旧小组件开关`() {
        Local.setSettings(Settings().apply {
            setBookmarkSurfaceVisible(BookmarkSurface.WIDGET, false)
        })
        assertTrue(boolField("widgetHideBookmarkButton"))
    }

    @Test
    fun `关掉的是新版才有的卡面时两个旧开关都保持关闭`() {
        Local.setSettings(Settings().apply {
            setBookmarkSurfaceVisible(BookmarkSurface.RANK, false)
        })
        assertFalse(boolField("hideStarButtonAtMyCollection"))
        assertFalse(boolField("widgetHideBookmarkButton"))
    }

    // ── 集合语义 ────────────────────────────────────────────────────

    @Test
    fun `空集等于全部显示，认不出的键只忽略`() {
        val settings = Settings()
        assertTrue(allVisible(settings))
        assertEquals(0, settings.hiddenBookmarkSurfaceCount)
        settings.hiddenBookmarkSurfaces.add("this_surface_no_longer_exists")
        assertTrue("认不出的键不该连坐别的卡面", allVisible(settings))
        // 但它确实在集合里，摘要数要跟着它走——用户看得见的「已隐藏 N」得对得上
        assertEquals(1, settings.hiddenBookmarkSurfaceCount)
    }

    @Test
    fun `每个卡面的显隐都能往返`() {
        val settings = Settings()
        for (surface in BookmarkSurface.entries) {
            settings.setBookmarkSurfaceVisible(surface, false)
            assertFalse(surface.name, settings.isBookmarkSurfaceVisible(surface))
            settings.setBookmarkSurfaceVisible(surface, true)
            assertTrue(surface.name, settings.isBookmarkSurfaceVisible(surface))
        }
    }

    @Test
    fun `显隐与键集合一起经过一次存盘读盘`() {
        Local.setSettings(Settings().apply {
            setBookmarkSurfaceVisible(BookmarkSurface.SEARCH, false)
            setBookmarkSurfaceVisible(BookmarkSurface.WIDGET, false)
        })
        val loaded = Local.getSettings()
        assertFalse(loaded.isBookmarkSurfaceVisible(BookmarkSurface.SEARCH))
        assertFalse(loaded.isBookmarkSurfaceVisible(BookmarkSurface.WIDGET))
        assertTrue(loaded.isBookmarkSurfaceVisible(BookmarkSurface.HOME_ILLUST))
        assertEquals(2, loaded.hiddenBookmarkSurfaceCount)
    }

    private fun allVisible(settings: Settings) =
        BookmarkSurface.entries.all { settings.isBookmarkSurfaceVisible(it) }

    private fun seed(json: String) {
        prefs.edit().putString(Local.SETTINGS, json).commit()
    }

    private fun rawSettings() =
        JsonParser.parseString(prefs.getString(Local.SETTINGS, "")).asJsonObject

    private fun boolField(name: String) = rawSettings().get(name).asBoolean
}
