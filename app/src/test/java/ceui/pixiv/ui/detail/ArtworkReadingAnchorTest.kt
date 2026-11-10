package ceui.pixiv.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkReadingAnchorTest {

    private val keys = listOf("hero", "tags", "comments")
    private val indexOf: (String) -> Int = keys::indexOf

    @Test
    fun `same arrangement leaves restoration to the recycler view`() {
        val fromList = ArtworkReadingAnchor(staged = false, page = 5, infoKey = null, infoOffset = 0)
        val fromStage = ArtworkReadingAnchor(staged = true, page = 5, infoKey = "comments", infoOffset = -40)
        assertEquals(ArtworkListLanding.Keep, fromList.landingIn(stagedNow = false, indexOf))
        assertEquals(ArtworkListLanding.Keep, fromStage.landingIn(stagedNow = true, indexOf))
    }

    @Test
    fun `reading pages in the list opens the info column at its top`() {
        val anchor = ArtworkReadingAnchor(staged = false, page = 5, infoKey = null, infoOffset = 0)
        assertEquals(ArtworkListLanding.Item(0, 0), anchor.landingIn(stagedNow = true, indexOf))
    }

    @Test
    fun `reading an info block keeps that block across arrangements`() {
        val fromList = ArtworkReadingAnchor(staged = false, page = -1, infoKey = "comments", infoOffset = -40)
        assertEquals(ArtworkListLanding.Item(2, -40), fromList.landingIn(stagedNow = true, indexOf))
        // 信息栏被滑离顶部说明在读信息：即使舞台翻过页，也接回那个区块
        val fromStage = ArtworkReadingAnchor(staged = true, page = 5, infoKey = "tags", infoOffset = 12)
        assertEquals(ArtworkListLanding.Item(1, 12), fromStage.landingIn(stagedNow = false, indexOf))
    }

    @Test
    fun `stage page is carried into the list when the info column sat at its top`() {
        val anchor = ArtworkReadingAnchor(staged = true, page = 5, infoKey = null, infoOffset = 0)
        assertEquals(ArtworkListLanding.Page(5), anchor.landingIn(stagedNow = false, indexOf))
    }

    @Test
    fun `first stage page lands on the top of the list`() {
        val anchor = ArtworkReadingAnchor(staged = true, page = 0, infoKey = null, infoOffset = 0)
        assertEquals(ArtworkListLanding.Item(0, 0), anchor.landingIn(stagedNow = false, indexOf))
    }

    @Test
    fun `missing info block falls back instead of an unrelated index`() {
        val toStage = ArtworkReadingAnchor(staged = false, page = -1, infoKey = "related#42", infoOffset = 0)
        assertEquals(ArtworkListLanding.Item(0, 0), toStage.landingIn(stagedNow = true, indexOf))
        val toList = ArtworkReadingAnchor(staged = true, page = 3, infoKey = "related#42", infoOffset = 0)
        assertEquals(ArtworkListLanding.Page(3), toList.landingIn(stagedNow = false, indexOf))
    }
}
