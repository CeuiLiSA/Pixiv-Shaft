package ceui.pixiv.ui.bulk

import ceui.lisa.models.IllustsBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BulkSelectStorageTest {

    @Test
    fun `put snapshots input and consume clears the slot`() {
        val first = IllustsBean()
        val input = mutableListOf(first)

        BulkSelectStorage.put(input)
        input += IllustsBean()

        val consumed = BulkSelectStorage.consume()
        assertEquals(1, consumed?.size)
        assertSame(first, consumed?.single())
        assertNull(BulkSelectStorage.consume())
    }

    @Test
    fun `oversized input is capped without retaining the source list`() {
        val input = MutableList(20_001) { IllustsBean() }

        BulkSelectStorage.put(input)
        input.clear()

        assertEquals(20_000, BulkSelectStorage.consume()?.size)
        assertNull(BulkSelectStorage.consume())
    }
}
