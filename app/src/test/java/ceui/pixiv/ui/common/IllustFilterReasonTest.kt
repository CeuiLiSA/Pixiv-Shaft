package ceui.pixiv.ui.common

import ceui.pixiv.api.model.Illust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 「跑全」模式下不可见作品的原因上报：pixiv 侧不给看的作品关哪条本地过滤都放不出来，
 * 只能报 INVISIBLE，不能把它顺带踩中的 R18/AI 列进作者页空态清单。
 */
class IllustFilterReasonTest {

    @Test
    fun `invisible work reports only INVISIBLE even if it is also R18 and AI`() {
        val reasons = mutableListOf<ContentFilterReason>()
        val illust = Illust(id = 1L, visible = false, x_restrict = 1, illust_ai_type = 2)
        val passes = IllustFeedItem.passesContentFilters(
            illust, skipR18Filter = false, skipMuteUserFilter = true, onRejected = { reasons += it },
        )
        assertFalse(passes)
        assertEquals(listOf(ContentFilterReason.INVISIBLE), reasons)
    }
}
