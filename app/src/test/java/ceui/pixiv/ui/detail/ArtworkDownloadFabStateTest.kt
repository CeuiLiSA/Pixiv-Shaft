package ceui.pixiv.ui.detail

import android.app.Application
import ceui.lisa.core.DownloadItem
import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaSinglePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证详情页 FAB 在模式 2（不自动下载）、用户手动暂停、下载中与下载完成等各种场景下的状态推导。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ArtworkDownloadFabStateTest {

    private fun singlePage() = Illust(
        id = 12345L,
        title = "test",
        type = "illust",
        create_date = "2026-10-06T00:00:00+09:00",
        page_count = 1,
        image_urls = ImageUrls(large = "https://img.test/12345_p0.jpg"),
        meta_single_page = MetaSinglePage(original_image_url = "https://img.test/12345_p0.png"),
    )

    private fun createItem(
        state: Int = DownloadItem.DownloadState.INIT,
        isPaused: Boolean = false,
        nonius: Int = 0,
    ): DownloadItem {
        val item = DownloadItem(singlePage(), 0)
        item.state = state
        item.isPaused = isPaused
        item.nonius = nonius
        return item
    }

    @Test
    fun `队列清空时推导为 Done`() {
        val result = resolveDownloadFabState(
            myItems = emptyList(),
            pageCount = 3,
        )
        assertEquals(DownloadFab.Done, result)
    }

    @Test
    fun `模式 2 下单页作品入列推导为 Paused 0 百分比`() {
        val items = listOf(createItem(state = DownloadItem.DownloadState.INIT, isPaused = true))
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 1,
        )
        assertTrue(result is DownloadFab.Paused)
        assertEquals(0, (result as DownloadFab.Paused).percent)
    }

    @Test
    fun `模式 2 下多页作品已完成部分页后推导为 Paused 且计算已完成进度`() {
        // 总共 5 页，剩下 3 页未完成（说明已完成 2 页 = 40%）
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = true),
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = true),
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = true),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 5,
        )
        assertTrue(result is DownloadFab.Paused)
        assertEquals(40, (result as DownloadFab.Paused).percent)
    }

    @Test
    fun `非模式 2 下条目被全部显式暂停时推导为 Paused`() {
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = true),
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = true),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 2,
        )
        assertTrue(result is DownloadFab.Paused)
        assertEquals(0, (result as DownloadFab.Paused).percent)
    }

    @Test
    fun `模式 2 下用户恢复下载后即便暂时无传输中的项也保持 Downloading 态而非置回 Paused`() {
        // 用户手动启动了该作品后，所有条目解除暂停（isPaused = false），但可能因为排队或切页间隙暂无 DOWNLOADING 项
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 2,
        )
        assertTrue(result is DownloadFab.Downloading)
        assertEquals(0, (result as DownloadFab.Downloading).percent)
    }

    @Test
    fun `正在下载的条目即便在模式 2 也推导为 Downloading`() {
        // 用户手动启动了该作品后，有条目进入 DOWNLOADING 态
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.DOWNLOADING, isPaused = false, nonius = 50),
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 2,
        )
        assertTrue(result is DownloadFab.Downloading)
        // 已完成 0 页，active nonius 50，总进度 (0 + 50) / 2 = 25%
        assertEquals(25, (result as DownloadFab.Downloading).percent)
    }

    @Test
    fun `模式 1 且处于蜂窝网络等待态时推导为 Resume 态`() {
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 1,
            isWifiConnected = false,
            downloadLimitType = 1,
        )
        assertTrue(result is DownloadFab.Resume)
        assertEquals(0, (result as DownloadFab.Resume).percent)
    }

    @Test
    fun `模式 1 处于蜂窝网络等待态时多页已完成部分页计算已完成进度并推导为 Resume 态`() {
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 5,
            isWifiConnected = false,
            downloadLimitType = 1,
        )
        assertTrue(result is DownloadFab.Resume)
        assertEquals(60, (result as DownloadFab.Resume).percent)
    }

    @Test
    fun `模式 1 处于蜂窝网络但已有活跃传输项时（如用户手动继续）推导为 Downloading 态`() {
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.DOWNLOADING, isPaused = false, nonius = 30),
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 2,
            isWifiConnected = false,
            downloadLimitType = 1,
        )
        assertTrue(result is DownloadFab.Downloading)
        assertEquals(15, (result as DownloadFab.Downloading).percent)
    }

    @Test
    fun `模式 1 处于 Wi-Fi 网络下条目推导为 Downloading 态`() {
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 1,
            isWifiConnected = true,
            downloadLimitType = 1,
        )
        assertTrue(result is DownloadFab.Downloading)
        assertEquals(0, (result as DownloadFab.Downloading).percent)
    }

    @Test
    fun `模式 0 无限制处于蜂窝网络下条目推导为 Downloading 态`() {
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.INIT, isPaused = false),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 1,
            isWifiConnected = false,
            downloadLimitType = 0,
        )
        assertTrue(result is DownloadFab.Downloading)
        assertEquals(0, (result as DownloadFab.Downloading).percent)
    }

    @Test
    fun `还有未完成项时进度封顶在 99`() {
        val items = listOf(
            createItem(state = DownloadItem.DownloadState.DOWNLOADING, nonius = 99),
        )
        val result = resolveDownloadFabState(
            myItems = items,
            pageCount = 1,
        )
        assertEquals(DownloadFab.Downloading(99), result)
    }
}
