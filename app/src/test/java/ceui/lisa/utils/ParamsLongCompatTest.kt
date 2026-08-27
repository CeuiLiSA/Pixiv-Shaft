package ceui.lisa.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ParamsLongCompatTest {

    @Test
    fun `legacy int user id is widened without becoming zero`() {
        assertEquals(123_456_789L, Params.coerceLong(123_456_789))
    }

    @Test
    fun `long user id keeps values beyond int range`() {
        val userId = Int.MAX_VALUE.toLong() + 42L
        assertEquals(userId, Params.coerceLong(userId))
    }

    @Test
    fun `missing or non numeric extras stay invalid`() {
        assertEquals(0L, Params.coerceLong(null))
        assertEquals(0L, Params.coerceLong("123"))
    }
}
