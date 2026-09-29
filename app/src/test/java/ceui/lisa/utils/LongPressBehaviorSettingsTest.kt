package ceui.lisa.utils

import ceui.lisa.activities.Shaft
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
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
 * 「插画大图长按行为」三态的存取与旧版开关兼容。
 *
 * 走 Robolectric 是硬要求：[Settings] 的静态字段靠 blankj `PathUtils` 取外部路径，纯 JVM 下类初始化直接抛
 * `ExceptionInInitializerError`（同 `ViewerDismissSettingsTest` 的坑）。
 *
 * 迁移那几条的复现路径刻意用裸 `Gson().fromJson`：Gson 走 Unsafe 建对象、**不跑字段初始化器**，所以旧版
 * 备份里没有的 `longPressBehavior` 反序列化后就是 0——这正是线上「旧备份 / 云端还原」的真实入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = LongPressBehaviorSettingsTest.TestApplication::class)
// 旧字段与其读写就是本类要钉的兼容契约，被弃用是预期。
@Suppress("DEPRECATION")
class LongPressBehaviorSettingsTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        Shaft.sSettings = Settings()
    }

    @Test
    fun `default behaviour is none`() {
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, Settings().longPressBehavior)
    }

    @Test
    fun `every behaviour value round trips`() {
        for (value in listOf(
            Settings.LONG_PRESS_BEHAVIOR_NONE,
            Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL,
            Settings.LONG_PRESS_BEHAVIOR_RESET_MIN,
        )) {
            val settings = Settings()
            settings.longPressBehavior = value
            assertEquals(value, settings.longPressBehavior)
        }
    }

    @Test
    fun `out of range falls back to none instead of clamping`() {
        val settings = Settings()
        settings.longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_RESET_MIN
        settings.longPressBehavior = -1
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, settings.longPressBehavior)
        settings.longPressBehavior = 3
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, settings.longPressBehavior)
    }

    @Test
    fun `writing a behaviour keeps the legacy flag in sync`() {
        val settings = Settings()
        settings.longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_NONE
        assertFalse(settings.isUseCustomLongPressReset)
        settings.longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL
        assertTrue(settings.isUseCustomLongPressReset)
        settings.longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_RESET_MIN
        assertTrue(settings.isUseCustomLongPressReset)
    }

    @Test
    fun `deprecated setter still maps onto the tri state`() {
        val settings = Settings()
        settings.isUseCustomLongPressReset = true
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_RESET_MIN, settings.longPressBehavior)
        settings.isUseCustomLongPressReset = false
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, settings.longPressBehavior)
    }

    @Test
    fun `legacy backup with the switch on migrates to reset to minimum`() {
        val legacy = Gson().fromJson("""{"useCustomLongPressReset":true}""", Settings::class.java)
        Settings.migrateLegacyLongPressBehavior(legacy)
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_RESET_MIN, legacy.longPressBehavior)
    }

    @Test
    fun `legacy backup with the switch off stays none`() {
        val legacy = Gson().fromJson("""{"useCustomLongPressReset":false}""", Settings::class.java)
        Settings.migrateLegacyLongPressBehavior(legacy)
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, legacy.longPressBehavior)
    }

    @Test
    fun `legacy backup without either field stays none`() {
        val legacy = Gson().fromJson("""{"themeIndex":1}""", Settings::class.java)
        Settings.migrateLegacyLongPressBehavior(legacy)
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_NONE, legacy.longPressBehavior)
    }

    @Test
    fun `migration never remaps a value written by this version`() {
        // 「优先缩小一级」写盘时会把旧字段回填成 true，再读回来必须仍是「优先缩小一级」——
        // 判定条件不能只看旧字段为真。
        val settings = Settings()
        settings.longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL
        assertTrue(settings.isUseCustomLongPressReset)
        Settings.migrateLegacyLongPressBehavior(settings)
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL, settings.longPressBehavior)
    }

    @Test
    fun `migration backfills the legacy flag for older readers`() {
        // 降级回旧版时，长按至少要落到「复原至最小」而不是空动作。
        val settings = Settings()
        settings.longPressBehavior = Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL
        settings.isUseCustomLongPressReset = false
        Settings.migrateLegacyLongPressBehavior(settings)
        assertTrue(settings.isUseCustomLongPressReset)
        assertEquals(Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL, settings.longPressBehavior)
    }
}
