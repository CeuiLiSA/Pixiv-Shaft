package ceui.lisa.utils

import ceui.lisa.activities.Shaft
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 动图播放按钮位置设置项（居中 / 靠下）的档位取值。
 *
 * 档位值直接当 `CheckableDialogBuilder` 的 which 用（见 `FragmentSettingsViewing`），
 * 所以常量必须 0/1 连号、顺序与弹窗选项逐条对齐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = SettingsUgoiraPlayButtonPositionTest.TestApplication::class)
class SettingsUgoiraPlayButtonPositionTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @Test
    fun `defaults to center`() {
        assertEquals(
            Settings.UGOIRA_PLAY_BUTTON_POSITION_CENTER,
            Settings().ugoiraPlayButtonPosition,
        )
    }

    @Test
    fun `position constants are contiguous and in dialog order`() {
        assertEquals(0, Settings.UGOIRA_PLAY_BUTTON_POSITION_CENTER)
        assertEquals(1, Settings.UGOIRA_PLAY_BUTTON_POSITION_BOTTOM)
    }

    @Test
    fun `every valid position round-trips`() {
        val settings = Settings()
        for (pos in Settings.UGOIRA_PLAY_BUTTON_POSITION_CENTER..Settings.UGOIRA_PLAY_BUTTON_POSITION_BOTTOM) {
            settings.ugoiraPlayButtonPosition = pos
            assertEquals(pos, settings.ugoiraPlayButtonPosition)
        }
    }

    @Test
    fun `out of range falls back to center`() {
        val settings = Settings()
        settings.ugoiraPlayButtonPosition = -1
        assertEquals(Settings.UGOIRA_PLAY_BUTTON_POSITION_CENTER, settings.ugoiraPlayButtonPosition)
        settings.ugoiraPlayButtonPosition = Settings.UGOIRA_PLAY_BUTTON_POSITION_BOTTOM + 1
        assertEquals(Settings.UGOIRA_PLAY_BUTTON_POSITION_CENTER, settings.ugoiraPlayButtonPosition)
    }
}
