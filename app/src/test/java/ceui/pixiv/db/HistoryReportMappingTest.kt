package ceui.pixiv.db

import ceui.lisa.database.IllustHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #989 本地历史 → 云端上报条目的映射。重点钉两件事:
 * 1. viewed_at 必须带真实浏览时间(0/负值不带) —— 回填不打乱云端顺序全靠它;
 * 2. 坏行(空/非法/非 object JSON)必须映射成 null 被跳过 —— 服务端整批校验,
 *    一条不合法会 400 掉整批并中止整个回填。
 */
class HistoryReportMappingTest {

    private fun entity(id: Int, type: Int, json: String?, time: Long) =
        IllustHistoryEntity().also {
            it.illustID = id
            it.type = type
            it.illustJson = json
            it.time = time
        }

    @Test
    fun `illust entity maps with real viewed_at`() {
        val item = entity(100, 0, """{"id":100,"type":"illust"}""", 1234L).toHistoryReportItem()!!
        assertEquals("illust", item.target_type)
        assertEquals(100L, item.target_id)
        assertEquals(1234L, item.viewed_at)
    }

    @Test
    fun `manga json in illust tab maps to manga target type`() {
        val item = entity(101, 0, """{"id":101,"type":"manga"}""", 5L).toHistoryReportItem()!!
        assertEquals("manga", item.target_type)
    }

    @Test
    fun `novel type maps to novel target type`() {
        val item = entity(102, 1, """{"id":102}""", 5L).toHistoryReportItem()!!
        assertEquals("novel", item.target_type)
    }

    @Test
    fun `zero time omits viewed_at instead of sending an invalid one`() {
        val item = entity(103, 0, """{"id":103}""", 0L).toHistoryReportItem()!!
        assertNull(item.viewed_at)
    }

    @Test
    fun `broken rows map to null so they cannot 400 the whole batch`() {
        assertNull(entity(0, 0, """{"id":1}""", 5L).toHistoryReportItem())      // 无效 id
        assertNull(entity(104, 0, null, 5L).toHistoryReportItem())              // 空 json
        assertNull(entity(105, 0, "not json", 5L).toHistoryReportItem())        // 非法 json
        assertNull(entity(106, 0, "[1,2,3]", 5L).toHistoryReportItem())         // 非 object
    }

    @Test
    fun `user history maps with updatedTime as viewed_at`() {
        val ge = GeneralEntity(678L, """{"id":678,"name":"x"}""", EntityType.USER, RecordType.VIEW_USER_HISTORY, 9999L)
        val item = ge.toUserHistoryReportItem()!!
        assertEquals("user", item.target_type)
        assertEquals(678L, item.target_id)
        assertEquals(9999L, item.viewed_at)
    }

    @Test
    fun `user history broken rows map to null`() {
        assertNull(GeneralEntity(0L, """{"a":1}""", EntityType.USER, RecordType.VIEW_USER_HISTORY, 1L).toUserHistoryReportItem())
        assertNull(GeneralEntity(1L, "", EntityType.USER, RecordType.VIEW_USER_HISTORY, 1L).toUserHistoryReportItem())
        assertNull(GeneralEntity(2L, "oops", EntityType.USER, RecordType.VIEW_USER_HISTORY, 1L).toUserHistoryReportItem())
    }
}
