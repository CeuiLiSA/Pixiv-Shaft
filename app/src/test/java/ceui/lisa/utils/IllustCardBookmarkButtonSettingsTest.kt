package ceui.lisa.utils

import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「插画列表显示收藏按钮」的默认值契约：新装与升级（旧设置 JSON 里没有这个 key）都必须是「显示」。
 * 默认值一旦被 Gson 读成 false，升级后全站瀑布流卡片的爱心会一起消失。
 *
 * 走 Robolectric：`Settings` 的 static 字段要 blankj `PathUtils` 取外部路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class IllustCardBookmarkButtonSettingsTest {

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `新装默认显示`() {
        assertTrue(Settings().isShowIllustCardBookmarkButton)
    }

    @Test
    fun `旧设置缺 key 时升级后仍显示`() {
        val legacy = Gson().fromJson("{\"hideStarButtonAtMyCollection\":true}", Settings::class.java)

        assertTrue(legacy.isShowIllustCardBookmarkButton)
        assertTrue(legacy.isHideStarButtonAtMyCollection)
    }

    @Test
    fun `关闭后存盘读盘保持关闭`() {
        val settings = Settings()
        settings.isShowIllustCardBookmarkButton = false

        val restored = Gson().fromJson(Gson().toJson(settings), Settings::class.java)

        assertFalse(restored.isShowIllustCardBookmarkButton)
    }
}
