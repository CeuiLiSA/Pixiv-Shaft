package ceui.pixiv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookmarkSurface] 的契约。它是落进设置的稳定键 + 弹窗的行序，两样都不能随手改：
 * 键改名 = 老配置里那条选择认不出来（只被忽略，用户以为开关自己跳回去了）；
 * 分组错位 = 弹窗里「常用」和「更多」不再连续。
 */
class BookmarkSurfaceTest {

    @Test
    fun `键两两不重复`() {
        val keys = BookmarkSurface.entries.map { it.key }
        assertEquals("有重复的 key，老配置会认错卡面", keys.size, keys.toSet().size)
    }

    @Test
    fun `键都是小写下划线，落盘后不会因为大小写或空格漂移`() {
        for (surface in BookmarkSurface.entries) {
            assertTrue(
                "key 必须是 [a-z_]+：${surface.name} -> ${surface.key}",
                surface.key.matches(Regex("[a-z_]+")),
            )
        }
    }

    @Test
    fun `每个卡面都有文案`() {
        for (surface in BookmarkSurface.entries) {
            assertTrue("${surface.name} 没有 labelRes", surface.labelRes != 0)
        }
    }

    @Test
    fun `ofKey 认得出自己的键，认不出的返回 null`() {
        for (surface in BookmarkSurface.entries) {
            assertEquals(surface, BookmarkSurface.ofKey(surface.key))
        }
        assertNull(BookmarkSurface.ofKey("this_surface_no_longer_exists"))
        assertNull(BookmarkSurface.ofKey(null))
    }

    @Test
    fun `两个分组加起来就是全部，且常用组连续排在更多组之前`() {
        val common = BookmarkSurface.commonGroup()
        val more = BookmarkSurface.moreGroup()
        assertEquals(BookmarkSurface.entries.size, common.size + more.size)
        assertTrue("常用组不该是空的", common.isNotEmpty())
        assertTrue("更多组不该是空的", more.isNotEmpty())
        val order = BookmarkSurface.entries.toList()
        val lastCommon = order.indexOfLast { it.group == BookmarkSurface.Group.COMMON }
        val firstMore = order.indexOfFirst { it.group == BookmarkSurface.Group.MORE }
        assertTrue("常用组必须是连续的一段且排在更多组之前", lastCommon < firstMore)
    }

    @Test
    fun `两个老开关对应的卡面还在`() {
        // migrateLegacyBookmarkSurfaces 就是往这两个 key 上种；改名会让老用户的选择静默丢失
        assertNotNull(BookmarkSurface.ofKey("my_collection"))
        assertNotNull(BookmarkSurface.ofKey("widget"))
    }
}
