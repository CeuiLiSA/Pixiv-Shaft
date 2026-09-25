package ceui.pixiv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveStaggerColumnsTest {

    @Test
    fun `phone portrait keeps the column setting`() {
        for (width in listOf(320f, 360f, 411f, 480f)) {
            assertEquals(2, AdaptiveStaggerColumns.columnsFor(width, 2))
        }
        assertEquals(3, AdaptiveStaggerColumns.columnsFor(411f, 3))
        assertEquals(4, AdaptiveStaggerColumns.columnsFor(411f, 4))
    }

    @Test
    fun `narrow list never drops below the column setting`() {
        assertEquals(4, AdaptiveStaggerColumns.columnsFor(300f, 4))
        assertEquals(2, AdaptiveStaggerColumns.columnsFor(0f, 2))
    }

    @Test
    fun `wide list adds columns while keeping card size`() {
        // 10 寸平板横屏整窗扣掉 88dp 侧栏
        assertEquals(6, AdaptiveStaggerColumns.columnsFor(1192f, 2))
        // Activity Embedding 3/7 窄栏
        assertEquals(2, AdaptiveStaggerColumns.columnsFor(548f, 2))
        // 折叠屏展开
        assertEquals(3, AdaptiveStaggerColumns.columnsFor(673f, 2))
    }

    @Test
    fun `invalid setting falls back to one column minimum`() {
        assertEquals(1, AdaptiveStaggerColumns.columnsFor(100f, 0))
    }
}
