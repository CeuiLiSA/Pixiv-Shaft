package ceui.lisa.utils

import android.content.Context
import android.content.SharedPreferences
import ceui.lisa.activities.Shaft
import ceui.pixiv.download.toast.DownloadToastKind
import ceui.pixiv.download.toast.DownloadToasts
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
import java.util.LinkedHashSet

/**
 * 「下载相关提示消息」的**旧升新 / 降级回填**：走真实的装载 / 落盘入口
 * （`Local.getSettings` / `Local.setSettings`），而不是直接调
 * `Settings.migrateLegacyDownloadToasts` —— 后者证明不了迁移真的接在那两处。
 *
 * 旧版设置 JSON 里只有总开关 `toastDownloadResult`，没有 `mutedDownloadToasts`；Gson 走 Unsafe
 * 建对象、**不跑字段初始化器**，所以新字段读出来是 null。这正是「装上新版后第一次读设置」的
 * 真实形态，也是本迁移唯一能看见的入口。
 *
 * 另一半是**降级**：新版写盘时必须把旧总开关回填对，否则用户退回旧版后会看到提示全开，
 * 以为自己在设置里安静掉的那些又回来了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = DownloadToastSettingsUpgradeTest.TestApplication::class)
@Suppress("DEPRECATION")
class DownloadToastSettingsUpgradeTest {
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
        // 真实的 Shaft.onCreate 干的就是这两件事，测试里手工补上。
        Shaft.sGson = Gson()
        Shaft.sPreferences = prefs
        Shaft.sSettings = Settings()
    }

    // ── 旧升新 ──────────────────────────────────────────────────────

    @Test
    fun `旧总开关关着时 它当年管的那四条消息仍然是安静的`() {
        seed("""{"toastDownloadResult":false}""")
        assertEquals(
            setOf(
                DownloadToastKind.DOWNLOAD_DONE,
                DownloadToastKind.DOWNLOAD_FAILED,
                DownloadToastKind.ARIA2,
                DownloadToastKind.NOVEL_AUTO_DOWNLOAD,
            ),
            DownloadToasts.quietKinds(Local.getSettings().mutedDownloadToasts),
        )
    }

    @Test
    fun `旧总开关开着时 升级后全部提示`() {
        seed("""{"toastDownloadResult":true}""")
        assertTrue(Local.getSettings().mutedDownloadToasts.isEmpty())
    }

    @Test
    fun `旧版 JSON 里连总开关都没有时按全开处理`() {
        seed("""{"themeIndex":1,"maxConcurrentDownloads":3}""")
        assertTrue(Local.getSettings().mutedDownloadToasts.isEmpty())
    }

    @Test
    fun `没有设置文件时默认全开`() {
        assertTrue(Local.getSettings().mutedDownloadToasts.isEmpty())
    }

    // ── 升级后写回：新字段落盘，不必每次重新推导 ─────────────────────

    @Test
    fun `升级后新字段落盘 不会再被旧开关反复改写`() {
        seed("""{"toastDownloadResult":false}""")
        Local.setSettings(Local.getSettings())
        assertEquals(4, rawSettings().getAsJsonArray("mutedDownloadToasts").size())
        // 再读一次仍应稳定：盘上已经有新字段，不再按旧开关推导。
        assertEquals(4, Local.getSettings().mutedDownloadToasts.size)
    }

    @Test
    fun `迁移过一次之后 用户把全部提示重新打开 不会再把四条老消息种回来`() {
        seed("""{"toastDownloadResult":false}""")
        // 第一次装载：按旧开关种下 4 条，并在落盘时记下「已经迁移过」。
        Local.setSettings(Local.getSettings())
        // 用户在新版弹窗里全部勾上（= 静音集合被清空）后确定。
        val reloaded = Local.getSettings()
        reloaded.setMutedDownloadToasts(LinkedHashSet())
        Local.setSettings(reloaded)
        assertTrue(Local.getSettings().mutedDownloadToasts.isEmpty())
    }

    // ── 降级：旧版只认一个总开关，回填不能漏 ───────────────────────────

    @Test
    fun `任一条被安静就把旧总开关回填成关`() {
        Local.setSettings(Settings().apply {
            setMutedDownloadToasts(LinkedHashSet(listOf(DownloadToastKind.BULK_SUMMARY.name)))
        })
        assertFalse(boolField("toastDownloadResult"))
    }

    @Test
    fun `全部恢复提示时旧总开关回填成开`() {
        Local.setSettings(Settings().apply { setMutedDownloadToasts(LinkedHashSet()) })
        assertTrue(boolField("toastDownloadResult"))
    }

    // ── 往返与容错 ──────────────────────────────────────────────────

    @Test
    fun `每一条消息都能安静 且安静集合原样往返`() {
        val all = LinkedHashSet(DownloadToastKind.entries.map { it.name })
        Local.setSettings(Settings().apply { setMutedDownloadToasts(all) })
        Shaft.sSettings = Local.getSettings()
        assertEquals(DownloadToastKind.entries.size, DownloadToasts.quietKinds(
            Shaft.sSettings.mutedDownloadToasts,
        ).size)
    }

    @Test
    fun `认不出的静音键不会让摘要多算一条`() {
        seed("""{"mutedDownloadToasts":["SOME_REMOVED_KIND",""]}""")
        assertEquals(0, DownloadToasts.quietKinds(Local.getSettings().mutedDownloadToasts).size)
    }

    private fun seed(json: String) {
        prefs.edit().putString(Local.SETTINGS, json).commit()
    }

    private fun rawSettings() = JsonParser.parseString(prefs.getString(Local.SETTINGS, "")).asJsonObject

    private fun boolField(name: String) = rawSettings().get(name).asBoolean
}