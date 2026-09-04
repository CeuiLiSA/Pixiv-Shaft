package ceui.pixiv.witstudio.theme

import ceui.pixiv.witstudio.R
import org.junit.Assert.assertEquals
import org.junit.Test

class WitRowStyleTest {

    @Test
    fun `single and empty groups use fully rounded background`() {
        assertEquals(R.drawable.wit_row_single, WitRowStyle.rowBackground(index = 0, total = 1))
        assertEquals(R.drawable.wit_row_single, WitRowStyle.rowBackground(index = 0, total = 0))
    }

    @Test
    fun `multi-row group maps first middle and last positions`() {
        assertEquals(R.drawable.wit_row_top, WitRowStyle.rowBackground(index = 0, total = 4))
        assertEquals(R.drawable.wit_row_mid, WitRowStyle.rowBackground(index = 1, total = 4))
        assertEquals(R.drawable.wit_row_mid, WitRowStyle.rowBackground(index = 2, total = 4))
        assertEquals(R.drawable.wit_row_bottom, WitRowStyle.rowBackground(index = 3, total = 4))
    }
}
