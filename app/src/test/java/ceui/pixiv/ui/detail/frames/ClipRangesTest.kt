package ceui.pixiv.ui.detail.frames

import org.junit.Assert.assertEquals
import org.junit.Test

/** 片段范围规则：时间轴把手、「设为起点 / 终点」与状态恢复共用这一份，边界一个都不能错。 */
class ClipRangesTest {

    @Test
    fun `没存过或存的值不完整时是整段`() {
        assertEquals(0..11, ClipRanges.clamp(null, 12))
        assertEquals(0..11, ClipRanges.clamp(intArrayOf(3), 12))
    }

    @Test
    fun `恢复的旧值被夹进帧数，且至少留两帧`() {
        // 旧版本记录的帧数更多：两端都越界。
        assertEquals(10..11, ClipRanges.clamp(intArrayOf(40, 60), 12))
        // 起点等于终点（单帧）：终点被推后一帧。
        assertEquals(5..6, ClipRanges.clamp(intArrayOf(5, 5), 12))
        // 起点在终点之后：终点被推到起点后一帧。
        assertEquals(7..8, ClipRanges.clamp(intArrayOf(7, 2), 12))
        assertEquals(0..1, ClipRanges.clamp(intArrayOf(-3, -1), 12))
    }

    @Test
    fun `设为起点：在终点之前只动起点`() {
        assertEquals(3..9, ClipRanges.withStart(0..9, 3, 12))
    }

    @Test
    fun `设为起点：越过终点时终点退回最后一帧`() {
        assertEquals(10..11, ClipRanges.withStart(2..6, 10, 12))
        // 正好落在终点上（剩一帧）也算越过。
        assertEquals(6..11, ClipRanges.withStart(2..6, 6, 12))
    }

    @Test
    fun `设为起点：落在最后一帧时起点让出一帧`() {
        assertEquals(10..11, ClipRanges.withStart(0..11, 11, 12))
    }

    @Test
    fun `设为终点：在起点之后只动终点`() {
        assertEquals(2..8, ClipRanges.withEnd(2..11, 8, 12))
    }

    @Test
    fun `设为终点：越过起点时起点退回第一帧`() {
        assertEquals(0..3, ClipRanges.withEnd(5..9, 3, 12))
        assertEquals(0..5, ClipRanges.withEnd(5..9, 5, 12))
    }

    @Test
    fun `设为终点：落在第一帧时终点让出一帧`() {
        assertEquals(0..1, ClipRanges.withEnd(0..11, 0, 12))
    }

    @Test
    fun `拖把手推不过对面，始终留两帧`() {
        assertEquals(4..9, ClipRanges.dragStart(0..9, 4))
        assertEquals(8..9, ClipRanges.dragStart(0..9, 12))
        assertEquals(3..4, ClipRanges.dragEnd(3..9, 0, 12))
        assertEquals(3..11, ClipRanges.dragEnd(3..9, 12, 12))
        assertEquals(3..6, ClipRanges.dragEnd(3..9, 7, 12))
    }

    @Test
    fun `文件名后缀按总帧数补零，序号从 1 起`() {
        assertEquals("_frame07", ClipRanges.frameSuffix(6, 12))
        assertEquals("_frame007", ClipRanges.frameSuffix(6, 150))
        assertEquals("_clip04-12", ClipRanges.clipSuffix(3..11, 12))
        assertEquals("_clip004-120", ClipRanges.clipSuffix(3..119, 150))
    }
}
