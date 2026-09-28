package ceui.lisa.utils

import android.content.Context
import android.content.SharedPreferences
import ceui.lisa.activities.Shaft
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
 * 「插画大图长按行为」的**旧升新**：走真实的装载 / 落盘入口（`Local.getSettings` / `Local.setSettings`），
 * 而不是直接调 `migrateLegacyLongPressBehavior`——后者证明不了迁移真的接在那两处。
 *
 * 旧版设置 JSON 里只有开关 `useCustomLongPressReset`，没有 `longPressBehavior`；Gson 走 Unsafe 建对象、
 * **不跑字段初始化器**，所以新字段读出来是 0。这正是「装上新版后第一次读设置」的真实形态。
 *
 * 另一半是**降级**：新版写盘时必须把旧开关回填对，否则用户退回旧版后长按会变成空动作（选过
 * 「优先缩小一级」时更是如此，旧版没有这一档，只能退化成「复原至最小」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = LongPressBehaviorUpgradeTest.TestApplication::class)
@Suppress("DEPRECATION")
class LongPressBehaviorUpgradeTest {
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
    fun `old settings with the switch on upgrade to reset to minimum`() {
        seed("""{"useCustomLongPressReset":true}""")
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_RESET_MIN, Local.getSettings().longPressBehavior)
    }

    @Test
    fun `old settings with the switch off upgrade to none`() {
        seed("""{"useCustomLongPressReset":false}""")
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, Local.getSettings().longPressBehavior)
    }

    @Test
    fun `old settings without the switch upgrade to none`() {
        seed("""{"themeIndex":1,"customZoomAddScale":1.8}""")
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, Local.getSettings().longPressBehavior)
    }

    @Test
    fun `missing settings key loads defaults`() {
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, Local.getSettings().longPressBehavior)
    }

    // ── 升级后写回：新字段落盘，不必每次重新推导 ─────────────────────

    @Test
    fun `upgrade persists the new field so it is not re-derived on every load`() {
        seed("""{"useCustomLongPressReset":true}""")
        Local.setSettings(Local.getSettings())
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_RESET_MIN, intField("longPressBehavior"))
        // 再读一次仍应稳定：新字段已经是 2，不会再被旧字段改写。
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_RESET_MIN, Local.getSettings().longPressBehavior)
    }

    // ── 降级：旧版只认两态，回填不能漏 ───────────────────────────────

    @Test
    fun `choosing reset to minimum writes the old switch on`() {
        Local.setSettings(Settings().apply {
            longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_RESET_MIN
        })
        assertTrue(boolField("useCustomLongPressReset"))
    }

    @Test
    fun `choosing shrink one level still leaves long press working on an older build`() {
        // 旧版没有「优先缩小一级」这一档，回填成开 = 旧版退化成「复原至最小」，
        // 而不是让长按变成空动作。
        Local.setSettings(Settings().apply {
            longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL
        })
        assertTrue(boolField("useCustomLongPressReset"))
    }

    @Test
    fun `choosing none writes the old switch off`() {
        Local.setSettings(Settings().apply {
            longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_NONE
        })
        assertFalse(boolField("useCustomLongPressReset"))
    }

    // ── 往返 ────────────────────────────────────────────────────────

    @Test
    fun `every behaviour survives a save and load round trip`() {
        for (value in listOf(
            Settings.LONG_PRESS_BEHAVIOR_NONE,
            Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL,
            Settings.LONG_PRESS_BEHAVIOR_RESET_MIN,
        )) {
            Local.setSettings(Settings().apply { longPressBehavior = value })
            assertEquals("value=$value", value, Local.getSettings().longPressBehavior)
        }
    }

    @Test
    fun `out of range value from a hand edited file falls back to none`() {
        seed("""{"longPressBehavior":7}""")
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, Local.getSettings().longPressBehavior)
    }

    private fun seed(json: String) {
        prefs.edit().putString(Local.SETTINGS, json).commit()
    }

    private fun rawSettings() = JsonParser.parseString(prefs.getString(Local.SETTINGS, "")).asJsonObject

    private fun intField(name: String) = rawSettings().get(name).asInt

    private fun boolField(name: String) = rawSettings().get(name).asBoolean
}
