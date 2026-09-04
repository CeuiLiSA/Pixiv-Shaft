package ceui.loxia

import ceui.lisa.models.IllustAIType
import ceui.lisa.models.ObjectSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContractsTest {

    @Test
    fun `novel keeps its own object pool namespace`() {
        val novel = Novel(id = 42L)

        assertEquals(42L, novel.objectUniqueId)
        assertEquals(ObjectSpec.KNovel, novel.objectType)
        assertTrue(novel.objectType != ObjectSpec.POST)
    }

    @Test
    fun `novel cover resolution prefers legacy cover then largest app api image`() {
        assertEquals(
            "legacy",
            Novel(coverUrl = "legacy", image_urls = ImageUrls(large = "large")).resolvedCoverUrl(),
        )
        assertEquals(
            "large",
            Novel(image_urls = ImageUrls(large = "large", medium = "medium")).resolvedCoverUrl(),
        )
        assertEquals("medium", Novel(image_urls = ImageUrls(medium = "medium")).resolvedCoverUrl())
    }

    @Test
    fun `copy helpers preserve identity for no-op and copy on state change`() {
        val novel = Novel(id = 7L, is_bookmarked = true)
        assertSame(novel, novel.withBookmarked(true))
        assertNotSame(novel, novel.withBookmarked(false))

        val user = User(id = 9L, is_followed = false)
        assertSame(user, user.withFollowed(false))
        assertNotSame(user, user.withFollowed(true))
    }

    @Test
    fun `user and image helpers retain product semantics`() {
        assertTrue(User(id = ConstantUser.pixiv).isOfficial())
        assertTrue(User(id = ConstantUser.CeuiLiSA).isVolunteer())
        assertFalse(User(x_restrict = 0).isR18Enabled())
        assertTrue(User(x_restrict = 2).isR18GEnabled())
        assertEquals("original", ImageUrls(original = "original", medium = "medium").findMaxSizeUrl())
        assertTrue(Novel(novel_ai_type = IllustAIType.CreatedByAI).isCreatedByAI())
    }
}
