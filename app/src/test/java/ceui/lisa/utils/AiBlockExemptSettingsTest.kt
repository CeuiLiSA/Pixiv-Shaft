package ceui.lisa.utils

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
 * 豁免作者「逐 ID 停用」在 [Settings] 这一层的契约。
 *
 * 盯两件事：
 *  1. 零迁移：老设置 / 老备份没有 `aiBlockExemptDisabledIds`，读出来必须是「名单里全部启用」；
 *  2. [Settings.isAiBlockClientSide] 只数**启用**的豁免作者 —— 它决定搜索走服务端剔 AI 还是
 *     拉全量本地滤，名单全停用时必须交回服务端。
 *
 * 走 Robolectric 的原因同 ViewerDismissSettingsTest：`Settings` 的 static 字段要 PathUtils。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class AiBlockExemptSettingsTest {

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `老备份缺停用字段时名单全部启用`() {
        val restored = Gson().fromJson(
            """{"aiBlockExemptAuthorIds":[100,200]}""",
            Settings::class.java,
        )

        assertTrue(restored.aiBlockExemptDisabledIds.isEmpty())
        assertEquals(2, restored.enabledAiBlockExemptAuthorCount)
        assertTrue(restored.isAiBlockExemptAuthor(100L))
        assertTrue(restored.isAiBlockClientSide)
    }

    @Test
    fun `停用字段显式为 null 时按空集处理`() {
        val restored = Gson().fromJson(
            """{"aiBlockExemptAuthorIds":[100],"aiBlockExemptDisabledIds":null}""",
            Settings::class.java,
        )

        assertTrue(restored.isAiBlockExemptAuthor(100L))
        assertEquals(1, restored.enabledAiBlockExemptAuthorCount)
    }

    @Test
    fun `部分停用只数启用的`() {
        val settings = Settings()
        settings.aiBlockExemptAuthorIds = linkedSetOf(100L, 200L, 300L)
        settings.aiBlockExemptDisabledIds = linkedSetOf(200L)

        assertEquals(2, settings.enabledAiBlockExemptAuthorCount)
        assertFalse(settings.isAiBlockExemptAuthor(200L))
        assertTrue(settings.isAiBlockExemptAuthor(300L))
        assertTrue(settings.isAiBlockClientSide)
    }

    @Test
    fun `名单全部停用且不模糊时交回服务端剔除`() {
        val settings = Settings()
        settings.aiBlockExemptAuthorIds = linkedSetOf(100L, 200L)
        settings.aiBlockExemptDisabledIds = linkedSetOf(100L, 200L)

        assertEquals(0, settings.enabledAiBlockExemptAuthorCount)
        assertFalse(settings.isAiBlockClientSide)
    }

    @Test
    fun `模糊粒子化时不论名单都走客户端`() {
        val settings = Settings()
        settings.aiBlockStrength = 1

        assertTrue(settings.isAiBlockClientSide)
    }

    @Test
    fun `停用集合随设置序列化往返`() {
        val settings = Settings()
        settings.aiBlockExemptAuthorIds = linkedSetOf(100L, 200L)
        settings.aiBlockExemptDisabledIds = linkedSetOf(200L)

        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(settings), Settings::class.java)

        assertEquals(linkedSetOf(100L, 200L), restored.aiBlockExemptAuthorIds)
        assertEquals(linkedSetOf(200L), restored.aiBlockExemptDisabledIds)
        assertFalse(restored.isAiBlockExemptAuthor(200L))
    }
}
