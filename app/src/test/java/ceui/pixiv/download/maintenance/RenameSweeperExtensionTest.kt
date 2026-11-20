package ceui.pixiv.download.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenameSweeperExtensionTest {

    @Test fun `extracts known image extension case insensitively even with hash or query in title`() {
        assertEquals("jpg", RenameSweeper.extractImageExtension("#tag?v=1_12345_p0.JPG"))
        assertEquals("jpeg", RenameSweeper.extractImageExtension("work.123.jpeg"))
        assertEquals("png", RenameSweeper.extractImageExtension("foo.bar.png"))
        assertEquals("webp", RenameSweeper.extractImageExtension("art_p0.WebP"))
    }

    @Test fun `ignores missing extension or dot inside title and falls back to next candidate`() {
        assertNull(RenameSweeper.extractImageExtension("12345_p0"))
        assertNull(RenameSweeper.extractImageExtension("title."))
        assertNull(RenameSweeper.extractImageExtension(".jpg"))
        assertNull(RenameSweeper.extractImageExtension("Vol.1", "Dr. Stone"))
        assertEquals("jpg", RenameSweeper.extractImageExtension("Vol.1", "Vol.1_p0.jpg"))
        assertEquals("png", RenameSweeper.extractImageExtension(null, "12345_p0.png"))
    }
}
