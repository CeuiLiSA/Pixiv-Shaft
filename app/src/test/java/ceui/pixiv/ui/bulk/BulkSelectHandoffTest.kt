package ceui.pixiv.ui.bulk

import ceui.pixiv.api.model.Illust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BulkSelectHandoffTest {

    @Test
    fun `put snapshots input and take removes the entry`() {
        val handoff = BulkSelectHandoff<Illust>("test")
        val first = Illust(id = 1L)
        val input = mutableListOf(first)

        val key = handoff.put(input)
        input += Illust(id = 2L)

        val taken = handoff.take(key)
        assertEquals(1, taken?.size)
        assertSame(first, taken?.single())
        assertNull(handoff.take(key))
        assertNull(handoff.take(null))
    }

    @Test
    fun `oversized input is capped without retaining the source list`() {
        val handoff = BulkSelectHandoff<Illust>("test")
        val input = MutableList(20_001) { Illust(id = it.toLong()) }

        val key = handoff.put(input)
        input.clear()

        assertEquals(20_000, handoff.take(key)?.size)
        assertNull(handoff.take(key))
    }

    @Test
    fun `two puts do not overwrite each other`() {
        val handoff = BulkSelectHandoff<Illust>("test")
        val a = handoff.put(listOf(Illust(id = 1L)))
        val b = handoff.put(listOf(Illust(id = 2L)))

        assertNotEquals(a, b)
        assertEquals(1L, handoff.take(a)?.single()?.id)
        assertEquals(2L, handoff.take(b)?.single()?.id)
    }

    @Test
    fun `oldest pending entry is evicted beyond maxPending`() {
        val handoff = BulkSelectHandoff<Illust>("test", maxPending = 2)
        val a = handoff.put(listOf(Illust(id = 1L)))
        val b = handoff.put(listOf(Illust(id = 2L)))
        val c = handoff.put(listOf(Illust(id = 3L)))

        assertNull(handoff.take(a))
        assertEquals(2L, handoff.take(b)?.single()?.id)
        assertEquals(3L, handoff.take(c)?.single()?.id)
    }
}
