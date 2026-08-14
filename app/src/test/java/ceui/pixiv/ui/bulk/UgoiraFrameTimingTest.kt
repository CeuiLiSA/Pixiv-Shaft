package ceui.pixiv.ui.bulk

import ceui.pixiv.ui.interpolate.RifeInterpolator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补帧 / 出片两侧的**时间轴**回归测试。
 *
 * 背景:补帧的收益全在「帧间隔更密且更匀」。历史上 [RifeInterpolator.splitDelays] 会把每段
 * 对齐到 10ms(GIF 的延迟粒度),于是 50ms 的原帧被劈成 **30+20** —— 插出来的那帧偏离正确
 * 时刻 20%,画面在每个原帧边界「一顿一快」,帧数翻倍了却不匀。播放改走 mp4(PTS 微秒级)后
 * 这个约束消失,量化下沉到 [gifFrameDelaysMs],只在真的要出 GIF 时做。
 *
 * 这两组断言就是那次修复的护栏:补帧侧必须**匀**,GIF 侧必须**总时长不漂**。
 */
class UgoiraFrameTimingTest {

    // ── 补帧侧:均匀 + 总时长守恒 ────────────────────────────────────────────

    @Test
    fun `2x 补帧把 50ms 均分成 25+25 而不是历史上的 30+20`() {
        assertEquals(listOf(25, 25), RifeInterpolator.splitDelays(listOf(50), 2))
    }

    @Test
    fun `2x 补帧对 70ms 同样均分`() {
        assertEquals(listOf(35, 35), RifeInterpolator.splitDelays(listOf(70), 2))
    }

    @Test
    fun `4x 补帧整除时完全均匀`() {
        assertEquals(listOf(20, 20, 20, 20), RifeInterpolator.splitDelays(listOf(80), 4))
    }

    @Test
    fun `除不尽时每段误差不超过 1ms 且一组之和精确等于原延迟`() {
        val split = RifeInterpolator.splitDelays(listOf(50), 4)
        assertEquals(50, split.sum())
        assertEquals(1, split.max() - split.min())
    }

    @Test
    fun `逐帧延迟不等的动图 每组各自守恒 总时长不变`() {
        val src = listOf(60, 70, 70, 100)
        val split = RifeInterpolator.splitDelays(src, 2)
        assertEquals(src.size * 2, split.size)
        assertEquals(src.sum(), split.sum())
        // 每一组(相邻 2 段)之和 = 对应原帧延迟 —— 补帧不会挪动原帧的时刻
        src.forEachIndexed { i, d ->
            assertEquals(d, split[i * 2] + split[i * 2 + 1])
        }
    }

    @Test
    fun `补帧后的帧间隔整体均匀 抖动不超过 1ms`() {
        // 20fps(50ms)的典型动图 2x 补帧:每一帧都该是 25ms,不能出现 30-20-30-20 的跛脚
        val split = RifeInterpolator.splitDelays(List(20) { 50 }, 2)
        assertEquals(40, split.size)
        assertTrue("补帧后帧间隔应当均匀,实际=$split", split.max() - split.min() <= 1)
    }

    @Test
    fun `倍率为 1 时原样返回`() {
        val src = listOf(40, 50)
        assertEquals(src, RifeInterpolator.splitDelays(src, 1))
    }

    // ── GIF 侧:量化到 10ms 但一圈总时长不漂 ──────────────────────────────

    @Test
    fun `均匀的 25ms 帧序列出 GIF 时总时长仍然精确`() {
        val gif = gifFrameDelaysMs(List(4) { 25 }, 4)
        assertEquals(listOf(30, 20, 30, 20), gif)
        assertEquals(100, gif.sum()) // 逐帧独立截断会得到 4×20=80(快 20%)
    }

    @Test
    fun `任意毫秒延迟量化后每帧都是 10ms 的整数倍`() {
        val gif = gifFrameDelaysMs(listOf(33, 33, 34), 3)
        assertTrue(gif.all { it % 10 == 0 })
        assertEquals(100, gif.sum())
    }

    @Test
    fun `单帧量化误差不超过 5ms 且不随帧累积`() {
        val src = List(50) { 25 }
        val gif = gifFrameDelaysMs(src, src.size)
        var srcElapsed = 0
        var gifElapsed = 0
        src.indices.forEach { i ->
            srcElapsed += src[i]
            gifElapsed += gif[i]
            assertTrue(
                "第 $i 帧累积偏差 ${gifElapsed - srcElapsed}ms 超过 5ms",
                Math.abs(gifElapsed - srcElapsed) <= 5,
            )
        }
        assertEquals(src.sum(), gif.sum())
    }

    @Test
    fun `延迟为 0 的病态帧也不会写出 0 延迟`() {
        // GIF 的 0 延迟会被看图器当「尽快播」处理,比慢一点更糟
        assertTrue(gifFrameDelaysMs(listOf(0, 0, 0), 3).all { it >= 10 })
    }
}
