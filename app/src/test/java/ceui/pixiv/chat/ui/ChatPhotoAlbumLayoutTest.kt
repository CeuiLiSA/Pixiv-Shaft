package ceui.pixiv.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPhotoAlbumLayoutTest {
    @Test
    fun twoPortraitsShareOneTallRow() {
        val (first, second) = layoutChatPhotoAlbum(220, 2, 48, listOf(0.55f, 0.65f))

        assertEquals(ChatPhotoCell(0, 0, 100, 214), first)
        assertEquals(ChatPhotoCell(102, 0, 118, 214), second)
    }

    @Test
    fun twoLandscapePhotosKeepATallerRowThanTheSamePairInALargerAlbum() {
        val pair = layoutChatPhotoAlbum(240, 2, 48, listOf(1.8f, 1.8f))
        val grid = layoutChatPhotoAlbum(240, 2, 48, List(4) { 1.8f })

        assertEquals(listOf(ChatPhotoCell(0, 0, 119, 131), ChatPhotoCell(121, 0, 119, 131)), pair)
        assertEquals(95, grid.first().height)
    }

    @Test
    fun threeLandscapePhotosUseOneWidePhotoAboveTwo() {
        val (first, second, third) = layoutChatPhotoAlbum(220, 2, 48, listOf(1.6f, 1.8f, 1.2f))

        assertEquals(ChatPhotoCell(0, 0, 220, 138), first)
        assertEquals(140, second.y)
        assertEquals(second.y, third.y)
        assertEquals(87, second.height)
        assertEquals(second.height, third.height)
        assertEquals(second.width + 2, third.x)
        assertEquals(220, third.x + third.width)
    }

    @Test
    fun landscapeGridKeepsTallerRowsAndEqualSeams() {
        val cells = layoutChatPhotoAlbum(240, 2, 48, List(9) { 1280f / 900f })

        assertEquals(listOf(0, 65, 130), cells.map { it.y }.distinct())
        assertTrue(cells.all { it.height == 63 })
        assertEquals(193, cells.last().y + cells.last().height)
    }

    @Test
    fun portraitLeadSpansBothRows() {
        val (first, second, third) = layoutChatPhotoAlbum(220, 2, 48, listOf(0.6f, 1.2f, 1.8f))

        assertEquals(0, first.x)
        assertEquals(0, second.y)
        assertEquals(first.width + 2, second.x)
        assertEquals(second.x, third.x)
        assertEquals(second.height + 2, third.y)
        assertEquals(first.height, third.y + third.height)
        assertEquals(220, third.x + third.width)
    }

    @Test
    fun supportedAlbumsFillTheirWidthWithoutOverlapping() {
        val aspects = listOf(2.4f, 0.5f, 1f, 1.25f, 0.7f, 2f, 0.4f, 1.6f, 1f)
        for (width in listOf(160, 220, 440)) for (count in 1..9) {
            val cells = layoutChatPhotoAlbum(width, 2, 48, aspects.take(count))
            assertEquals(count, cells.size)
            assertEquals(width, cells.maxOf { it.x + it.width })
            for ((index, cell) in cells.withIndex()) {
                assertTrue(cell.width >= 48 && cell.height >= 48)
                for (other in cells.drop(index + 1)) assertTrue(
                    cell.x + cell.width <= other.x || other.x + other.width <= cell.x ||
                        cell.y + cell.height <= other.y || other.y + other.height <= cell.y,
                )
            }
        }
    }
}
