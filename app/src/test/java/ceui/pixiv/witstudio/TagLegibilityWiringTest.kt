package ceui.pixiv.witstudio

import android.app.Application
import ceui.lisa.utils.Settings
import ceui.pixiv.ui.settings.TagLegibilityPrefs
import ceui.pixiv.witstudio.theme.V3TagLegibility
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 「标签原文亮暗度」从设备本地存储到 witstudio 的单向注入链路。
 *
 * 这两条**不在 [Settings] 里** —— 它们不具备跨设备性，放进去就会随 `Shaft-Backup.json` 与
 * 云端 `PUT /v1/settings/{uid}` 一起走；现在走独立 MMKV namespace 的 [TagLegibilityPrefs]
 * （先例：设置页「语言」的 `AppLocales`、`MuzeiPrefs`、`ReaderSettings`）。
 *
 * 这条链跨了两层，**编译器帮不上忙**：MMKV 的 key、100→1f 的换算、`V3TagLegibility` 的 setter
 * 名字，任何一处写错都不会编译报错，只会静默不生效 —— 用户拉了滑条、值也存了，但标签颜色
 * 纹丝不动。这里把它钉住。
 *
 * 每条用例结束都要把全局强度复位：[V3TagLegibility] 是进程级单例，`V3Palette.from()` 会读它，
 * 泄漏出去会污染同 JVM 里其它跑 `from()` 的测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class,
    shadows = [TagLegibilityWiringTest.MemoryMMKV::class],
    instrumentedPackages = ["com.tencent.mmkv"])
class TagLegibilityWiringTest {

    @Before
    fun setUp() {
        MemoryMMKV.values.clear()
    }

    @After
    fun reset() {
        V3TagLegibility.setBoosts(0f, 0f)
    }

    @Test
    fun `applyToWitStudio 把百分数换算成 0f 到 1f`() {
        TagLegibilityPrefs.save(0, 100)
        TagLegibilityPrefs.applyToWitStudio()
        assertEquals(0f, V3TagLegibility.boostFor(false), 0f)
        assertEquals(1f, V3TagLegibility.boostFor(true), 0f)
    }

    @Test
    fun `reset 把两条一起归零`() {
        TagLegibilityPrefs.save(40, 70)
        TagLegibilityPrefs.applyToWitStudio()
        assertEquals(0.4f, V3TagLegibility.boostFor(false), 0.0001f)
        assertEquals(0.7f, V3TagLegibility.boostFor(true), 0.0001f)
        TagLegibilityPrefs.reset()
        TagLegibilityPrefs.applyToWitStudio()
        assertEquals(0f, V3TagLegibility.boostFor(false), 0f)
        assertEquals(0f, V3TagLegibility.boostFor(true), 0f)
    }

    @Test
    fun `全新安装读出来是零`() {
        // 存储里没有键时两条都必须兜到 0，否则新装用户一开 app 就吃到一个非零的增强。
        assertEquals(0, TagLegibilityPrefs.light())
        assertEquals(0, TagLegibilityPrefs.dark())
        TagLegibilityPrefs.applyToWitStudio()
        assertEquals(0f, V3TagLegibility.boostFor(false), 0f)
        assertEquals(0f, V3TagLegibility.boostFor(true), 0f)
    }

    @Test
    fun `深浅两条互不影响`() {
        TagLegibilityPrefs.save(25, 75)
        assertEquals(25, TagLegibilityPrefs.light())
        assertEquals(75, TagLegibilityPrefs.dark())
    }

    @Test
    fun `越界的写入被钳住`() {
        TagLegibilityPrefs.save(-5, 142)
        assertEquals(0, TagLegibilityPrefs.light())
        assertEquals(100, TagLegibilityPrefs.dark())
        TagLegibilityPrefs.applyToWitStudio()
        assertEquals(0f, V3TagLegibility.boostFor(false), 0f)
        assertEquals(1f, V3TagLegibility.boostFor(true), 0f)
    }

    @Test
    fun `不进备份 —— Settings 的序列化里没有这两条`() {
        // 备份文件的 settings 段就是 `Shaft.sGson.toJson(Settings)`（`Shaft.sGson` 是裸
        // `new Gson()`，与这里用的同一个形状），云端 PUT 的 payload 同源。字段不在 Settings
        // 上，就天然不会被带走 —— 不需要任何导出侧排除名单，也不存在「还原后被清零」的取舍。
        TagLegibilityPrefs.save(80, 60)
        val json = Gson().toJson(Settings())
        assertFalse(json, json.contains("tagLegibilityBoost"))
        assertFalse(json, json.contains("boost_light"))
        assertFalse(json, json.contains("boost_dark"))
    }

    /**
     * 只替换 MMKV 的 JNI 存储，跑的是真实的 [TagLegibilityPrefs]。
     * 与 `ReaderParagraphSpacingMigrationTest` 里那份同款（该 shadow 目前是各测试类各自持有）。
     */
    @Implements(MMKV::class, isInAndroidSdk = false)
    class MemoryMMKV {
        @Implementation fun count(): Long = values.size.toLong()
        @Implementation fun containsKey(key: String): Boolean = values.containsKey(key)
        @Implementation fun removeValueForKey(key: String) { values.remove(key) }
        @Implementation fun decodeFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue
        @Implementation fun decodeInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        @Implementation fun decodeBool(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
        @Implementation fun decodeString(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue
        @Implementation fun encode(key: String, value: Float): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: Boolean): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: Int): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: Long): Boolean {
            values[key] = value
            return true
        }

        companion object {
            val values = mutableMapOf<String, Any>()
            @JvmStatic @Implementation fun mmkvWithID(id: String): MMKV = ReflectionHelpers.callConstructor(
                MMKV::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L),
            )
        }
    }
}
