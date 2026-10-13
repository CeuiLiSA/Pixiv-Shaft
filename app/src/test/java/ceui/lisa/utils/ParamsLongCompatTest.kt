package ceui.lisa.utils

import androidx.work.workDataOf
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

    @Test
    fun `legacy int in persisted work data is widened without becoming zero`() {
        // 升级前入队的 WidgetBookmarkWorker 任务 inputData 存的是 Int，Data.getLong 只认 Long 会回落 0
        val legacy = workDataOf("widget_bookmark_illust_id" to 123_456_789)
        assertEquals(0L, legacy.getLong("widget_bookmark_illust_id", 0L))
        assertEquals(123_456_789L, Params.coerceLong(legacy.keyValueMap["widget_bookmark_illust_id"]))
    }
}
