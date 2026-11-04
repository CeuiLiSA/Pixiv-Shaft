package ceui.pixiv.ui.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelDetailMathTest {

    @Test
    fun `封面缩略图去掉 c 段得到母图`() {
        assertEquals(
            "https://i.pximg.net/novel-cover-master/img/2026/10/26/00/18/36/ci29473168_abc_master1200.jpg",
            novelCoverMasterUrl(
                "https://i.pximg.net/c/240x480_80/novel-cover-master/img/2026/10/26/00/18/36/ci29473168_abc_master1200.jpg"
            ),
        )
    }

    @Test
    fun `换了镜像域名也认`() {
        assertEquals(
            "https://i.pixiv.re/novel-cover-master/img/a.jpg",
            novelCoverMasterUrl("https://i.pixiv.re/c/600x600/novel-cover-master/img/a.jpg"),
        )
    }

    @Test
    fun `默认封面和其它形状不算真封面`() {
        assertNull(novelCoverMasterUrl("https://s.pximg.net/common/images/novel_thumb/novel_thumb_1.jpg"))
        assertNull(novelCoverMasterUrl("https://i.pximg.net/novel-cover-master/img/a.jpg"))
        assertNull(novelCoverMasterUrl("https://i.pximg.net/c/240x480_80/img-master/img/a.jpg"))
        assertNull(novelCoverMasterUrl(""))
        assertNull(novelCoverMasterUrl(null))
    }

    @Test
    fun `阅读时长按每分钟 500 字四舍五入且至少 1 分钟`() {
        assertEquals(14, novelReadingMinutes(6940))
        assertEquals(1, novelReadingMinutes(1))
        assertEquals(1, novelReadingMinutes(749))
        assertEquals(2, novelReadingMinutes(750))
        assertNull(novelReadingMinutes(0))
        assertNull(novelReadingMinutes(null))
    }

    @Test
    fun `没读过不显示进度`() {
        assertNull(novelReadingPercent(charIndex = 0, pageIndex = 0, totalPages = 0, textLength = 6940))
        assertNull(novelReadingPercent(charIndex = 0, pageIndex = 0, totalPages = 30, textLength = 6940))
    }

    @Test
    fun `分页模式按页算`() {
        assertEquals(40, novelReadingPercent(charIndex = 2800, pageIndex = 11, totalPages = 30, textLength = 6940))
        assertEquals(100, novelReadingPercent(charIndex = 6900, pageIndex = 29, totalPages = 30, textLength = 6940))
    }

    @Test
    fun `滚动模式按字数算并夹在 1 到 100`() {
        assertEquals(42, novelReadingPercent(charIndex = 2915, pageIndex = 0, totalPages = 0, textLength = 6940))
        assertEquals(1, novelReadingPercent(charIndex = 3, pageIndex = 0, totalPages = 0, textLength = 6940))
        assertEquals(100, novelReadingPercent(charIndex = 9000, pageIndex = 0, totalPages = 0, textLength = 6940))
        assertNull(novelReadingPercent(charIndex = 100, pageIndex = 0, totalPages = 0, textLength = null))
    }
}
