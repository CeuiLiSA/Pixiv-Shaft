package ceui.pixiv.download

import ceui.pixiv.download.model.RelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelativePathTest {

    @Test fun `parses forward slashes`() {
        val p = RelativePath.parse("a/b/c.png")
        assertEquals(listOf("a", "b", "c.png"), p.segments)
        assertEquals("c.png", p.filename)
        assertEquals(listOf("a", "b"), p.directory)
    }

    @Test fun `parses backslashes as separators too`() {
        val p = RelativePath.parse("a\\b\\c.png")
        assertEquals(listOf("a", "b", "c.png"), p.segments)
    }

    @Test fun `drops empty segments`() {
        val p = RelativePath.parse("///a//b/")
        assertEquals(listOf("a", "b"), p.segments)
    }

    @Test fun `empty input throws`() {
        assertThrows(IllegalArgumentException::class.java) { RelativePath.parse("") }
        assertThrows(IllegalArgumentException::class.java) { RelativePath.parse("///") }
    }

    @Test fun `rejects segments containing path separators`() {
        assertThrows(IllegalArgumentException::class.java) {
            RelativePath(listOf("a/b", "c.png"))
        }
    }

    @Test fun `joinTo roundtrips`() {
        val p = RelativePath.parse("foo/bar/baz.txt")
        assertEquals("foo/bar/baz.txt", p.joinTo())
    }
}
