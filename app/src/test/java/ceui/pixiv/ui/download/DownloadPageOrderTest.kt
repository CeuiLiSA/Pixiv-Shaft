package ceui.pixiv.ui.download

import ceui.lisa.database.DownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPageOrderTest {

    private fun entity(fileName: String, page: Int = -1) = DownloadEntity().apply {
        this.fileName = fileName
        this.page = page
    }

    @Test fun `natural order sorts numeric runs by value not lexicographically`() {
        val names = listOf("a_p10.jpg", "a_p2.jpg", "a_p1.jpg", "a_p80.jpg", "a_p0.jpg")
        assertEquals(
            listOf("a_p0.jpg", "a_p1.jpg", "a_p2.jpg", "a_p10.jpg", "a_p80.jpg"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test fun `natural order handles digits in the middle of the name`() {
        assertEquals(
            listOf("xxx1xxx", "xxx2xxx", "xxx10xxx"),
            listOf("xxx10xxx", "xxx2xxx", "xxx1xxx").sortedWith(NaturalOrder),
        )
    }

    @Test fun `natural order is a total order for equal numeric values`() {
        // p01 == p1 数值相等，靠原串兜底稳定区分；两个方向必须互为相反
        val c = NaturalOrder.compare("p01", "p1")
        assertEquals(-c, NaturalOrder.compare("p1", "p01"))
        assertEquals(0, NaturalOrder.compare("p1", "p1"))
    }

    @Test fun `page column wins over file name`() {
        val rows = listOf(entity("z.jpg", page = 0), entity("a.jpg", page = 1))
        assertEquals(listOf("z.jpg", "a.jpg"), rows.sortedWith(DownloadPageOrder).map { it.fileName })
    }

    @Test fun `rows without page go after known pages in natural name order`() {
        val rows = listOf(
            entity("x_p10.jpg"),
            entity("x_p2.jpg", page = -2),
            entity("known_p1.jpg", page = 1),
            entity("known_p0.jpg", page = 0),
        )
        assertEquals(
            listOf("known_p0.jpg", "known_p1.jpg", "x_p2.jpg", "x_p10.jpg"),
            rows.sortedWith(DownloadPageOrder).map { it.fileName },
        )
    }
}
