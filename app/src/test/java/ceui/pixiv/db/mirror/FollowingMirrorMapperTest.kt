package ceui.pixiv.db.mirror

import android.app.Application
import ceui.lisa.activities.Shaft
import ceui.loxia.Novel
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.UserPreview
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关注书架借用了作品表的三列（见 [BookmarkMirrorMapper.fromUserPreview]），这里钉住它们的语义：
 * 借错一列，「最久没更新」排序和标签筛选就会静默地给出错的人，编译器不会说话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FollowingMirrorMapperTest {

    private val shelf = BookmarkShelf(123L, MirrorContentType.USER, MirrorRestrict.PRIVATE)

    @Before
    fun setUp() {
        Shaft.sGson = Gson()
    }

    @Test
    fun `last post time is the newest preview work across illusts and novels`() {
        val preview = UserPreview(
            user = User(id = 42L, name = "Alice", account = "alice_draws"),
            illusts = listOf(
                Illust(id = 1L, create_date = "2021-03-01T10:00:00+09:00"),
                Illust(id = 2L, create_date = "2019-01-01T10:00:00+09:00"),
            ),
            novels = listOf(Novel(id = 3L, create_date = "2023-06-14T21:03:11+09:00")),
        )

        val row = BookmarkMirrorMapper.fromUserPreview(shelf, preview, 7L, 3, 99L).row

        assertEquals(BookmarkMirrorMapper.parseCreateDate("2023-06-14T21:03:11+09:00"), row.createDateMs)
        assertEquals(shelf.key, row.shelfKey)
        assertEquals(42L, row.targetId)
        assertEquals(42L, row.authorId)
        assertEquals(MirrorRestrict.PRIVATE.code, row.restrictCode)
        assertEquals(BookmarkMirrorMapper.WORK_TYPE_USER, row.workType)
        assertEquals(7L, row.bookmarkSeq)
        assertEquals(3, row.generation)
    }

    @Test
    fun `user without works has unknown last post time`() {
        val row = BookmarkMirrorMapper.fromUserPreview(
            shelf, UserPreview(user = User(id = 5L, name = "Quiet")), 0L, 1, 0L,
        ).row
        assertEquals(0L, row.createDateMs)
        assertEquals(0, row.tagCount)
    }

    @Test
    fun `tags are the deduplicated union of preview works and search covers name and account`() {
        val preview = UserPreview(
            user = User(id = 42L, name = "Alice", account = "alice_draws"),
            illusts = listOf(
                Illust(id = 1L, tags = listOf(Tag(name = "原神"), Tag(name = "オリジナル", translated_name = "原创"))),
                Illust(id = 2L, tags = listOf(Tag(name = "原神"))),
            ),
            novels = listOf(Novel(id = 3L, tags = listOf(Tag(name = "Fantasy")))),
        )

        val mapped = BookmarkMirrorMapper.fromUserPreview(shelf, preview, 0L, 1, 0L)

        assertEquals(setOf("原神", "オリジナル", "fantasy"), mapped.tags.map { it.tagName }.toSet())
        assertEquals(mapped.tags.size, mapped.row.tagCount)
        assertTrue(mapped.tags.all { it.targetId == 42L && it.shelfKey == shelf.key })
        listOf("alice", "alice_draws", "原创", "fantasy").forEach {
            assertTrue("searchText 应包含 $it: ${mapped.row.searchText}", mapped.row.searchText.contains(it))
        }
    }

    @Test
    fun `payload round-trips to a renderable user preview`() {
        val preview = UserPreview(
            user = User(id = 42L, name = "Alice", is_followed = true),
            illusts = listOf(Illust(id = 1L, title = "work")),
            is_muted = true,
        )
        val row = BookmarkMirrorMapper.fromUserPreview(shelf, preview, 0L, 1, 0L).row
        assertTrue(row.isMuted)
        assertEquals(preview, Gson().fromJson(row.payloadJson, UserPreview::class.java))
    }
}
