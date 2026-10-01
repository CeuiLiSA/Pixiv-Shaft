package ceui.lisa.utils

import ceui.lisa.activities.Shaft
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「详情页跟随大图翻页滚动」的三档取值。
 *
 * 档位值直接当 `CheckableDialogBuilder` 的 which 用（见 `FragmentSettingsViewing`），
 * 所以常量必须 0/1/2 连号、顺序与弹窗选项逐条对齐 —— 顺序错位就是静默选错档，UI 上看不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = SettingsViewerViewportLinkTest.TestApplication::class)
class SettingsViewerViewportLinkTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @Test
    fun `defaults to not following`() {
        assertEquals(
            Settings.VIEWER_VIEWPORT_LINK_NONE,
            Settings().getViewerViewportLinkMode(),
        )
    }

    @Test
    fun `mode constants are contiguous and in dialog order`() {
        assertEquals(0, Settings.VIEWER_VIEWPORT_LINK_NONE)
        assertEquals(1, Settings.VIEWER_VIEWPORT_LINK_EXPANDED_ONLY)
        assertEquals(2, Settings.VIEWER_VIEWPORT_LINK_AUTO_EXPAND)
    }

    @Test
    fun `every valid mode round-trips`() {
        val settings = Settings()
        for (mode in Settings.VIEWER_VIEWPORT_LINK_NONE..Settings.VIEWER_VIEWPORT_LINK_AUTO_EXPAND) {
            settings.setViewerViewportLinkMode(mode)
            assertEquals(mode, settings.getViewerViewportLinkMode())
        }
    }

    @Test
    fun `out of range falls back to not following`() {
        val settings = Settings()
        settings.setViewerViewportLinkMode(-1)
        assertEquals(Settings.VIEWER_VIEWPORT_LINK_NONE, settings.getViewerViewportLinkMode())
        settings.setViewerViewportLinkMode(Settings.VIEWER_VIEWPORT_LINK_AUTO_EXPAND + 1)
        assertEquals(Settings.VIEWER_VIEWPORT_LINK_NONE, settings.getViewerViewportLinkMode())
    }
}
