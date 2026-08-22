package ceui.pixiv.ui.bulk

import ceui.loxia.Illust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BulkSelectStorageTest {

    @Test
    fun `put snapshots input and consume clears the slot`() {
        val first = Illust(id = 1L)
        val input = mutableListOf(first)

        BulkSelectStorage.put(input)
        input += Illust(id = 2L)

        val consumed = BulkSelectStorage.consume()
        assertEquals(1, consumed?.size)
        assertSame(first, consumed?.single())
        assertNull(BulkSelectStorage.consume())
    }

    @Test
    fun `oversized input is capped without retaining the source list`() {
        val input = MutableList(20_001) { Illust(id = it.toLong()) }

        BulkSelectStorage.put(input)
        input.clear()

        assertEquals(20_000, BulkSelectStorage.consume()?.size)
        assertNull(BulkSelectStorage.consume())
    }
}
