package ceui.lisa.core

import android.app.Application
import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaSinglePage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [Manager.isDispatchable] —— 详情页 FAB「继续」([Manager.startIllust]) 的派发范围。
 *
 * 口径：泵关着（仅 Wi-Fi 在蜂窝上 / 模式 2 / 冷启动恢复）时只派发被点名作品的页，
 * 其它作品的等待项哪怕未暂停也不能被顺带走 —— 否则点一个作品，整队都在蜂窝上跑起来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ManagerDispatchScopeTest {

    private fun item(illustId: Long, state: Int = DownloadItem.DownloadState.INIT, paused: Boolean = false) =
        DownloadItem(
            Illust(
                id = illustId,
                title = "t",
                type = "illust",
                create_date = "2026-10-08T00:00:00+09:00",
                page_count = 1,
                image_urls = ImageUrls(large = "https://img.test/${illustId}_p0.jpg"),
                meta_single_page = MetaSinglePage(original_image_url = "https://img.test/${illustId}_p0.png"),
            ),
            0,
        ).apply {
            this.state = state
            isPaused = paused
        }

    @Test
    fun `泵关着_只派发被放行作品的页`() {
        val released = setOf(1L)
        assertTrue(Manager.isDispatchable(item(1L), false, released))
        assertFalse(Manager.isDispatchable(item(2L), false, released))
    }

    @Test
    fun `泵关着且无放行_一条都不派发`() {
        assertFalse(Manager.isDispatchable(item(1L), false, emptySet()))
    }

    @Test
    fun `泵开着_所有未暂停的等待项照常派发`() {
        assertTrue(Manager.isDispatchable(item(2L), true, emptySet()))
    }

    @Test
    fun `暂停或非 INIT 的页不派发`() {
        val released = setOf(1L)
        assertFalse(Manager.isDispatchable(item(1L, paused = true), true, released))
        assertFalse(Manager.isDispatchable(item(1L, state = DownloadItem.DownloadState.DOWNLOADING), true, released))
    }
}
