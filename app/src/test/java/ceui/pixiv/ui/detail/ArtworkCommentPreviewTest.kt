package ceui.pixiv.ui.detail

import ceui.loxia.User
import ceui.pixiv.api.model.Comment
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkCommentPreviewTest {

    @Test
    fun `older preview response keeps locally acknowledged comments first`() {
        val local = comment(30L)
        val loaded = listOf(comment(20L), comment(10L))

        val merged = commentsItem()
            .prepend(local)
            .withComments(loaded)
            .comments.orEmpty()

        assertEquals(listOf(30L, 20L, 10L), merged.map { it.id })
    }

    @Test
    fun `preview merge orders newest local comment first and removes server duplicates`() {
        val olderLocal = comment(20L)
        val newerLocal = comment(30L)
        val loaded = listOf(comment(30L), comment(10L))

        val merged = commentsItem()
            .prepend(olderLocal)
            .prepend(newerLocal)
            .withComments(loaded)
            .comments.orEmpty()

        assertEquals(listOf(30L, 20L, 10L), merged.map { it.id })
    }

    private fun commentsItem() = ArtworkCommentsItem(
        illustId = 1,
        illustTitle = "title",
        illustAuthorId = 2,
    )

    private fun comment(id: Long) = Comment(id = id, user = User(id = id))
}
