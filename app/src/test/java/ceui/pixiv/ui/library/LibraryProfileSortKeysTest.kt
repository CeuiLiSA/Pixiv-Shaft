package ceui.pixiv.ui.library

import ceui.pixiv.db.mirror.BookmarkSort
import ceui.pixiv.db.mirror.MirrorContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 筛选面板把排序拆成「依据」胶囊 + 「方向」连通组，这组测试钉住拆分不丢档、不串档：
 * 每个书架可用的排序都恰好落在一个依据下，依据的第一个方向是切过去时的默认方向，
 * 同一依据下两个方向的文案不同（否则方向组两段写着同一句话）。
 */
class LibraryProfileSortKeysTest {

    private val profiles = MirrorContentType.entries.map(LibraryProfile::of)

    @Test
    fun `every sort lands in exactly one key, in declared order`() {
        profiles.forEach { profile ->
            assertEquals(profile.sorts, profile.sortKeys.flatMap { it.sorts })
        }
    }

    @Test
    fun `keys hold at most two directions and the default is the descending or newest one`() {
        val defaults = setOf(
            BookmarkSort.BOOKMARK_NEWEST,
            BookmarkSort.CREATED_NEWEST,
            BookmarkSort.POPULAR_DESC,
            BookmarkSort.RATIO_TALLEST,
            BookmarkSort.LENGTH_DESC,
        )
        profiles.forEach { profile ->
            profile.sortKeys.filter { it.sorts.size > 1 }.forEach { key ->
                assertEquals(2, key.sorts.size)
                assertTrue("${key.sorts} 的默认方向不对", key.sorts.first() in defaults)
                val labels = key.sorts.map(profile::sortDirectionLabel)
                assertEquals(labels.distinct(), labels)
            }
        }
    }

    @Test
    fun `following shelf keeps six sorts in four keys`() {
        val user = LibraryProfile.of(MirrorContentType.USER)
        assertEquals(listOf(2, 2, 1, 1), user.sortKeys.map { it.sorts.size })
    }
}
