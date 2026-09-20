package ceui.pixiv.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 轮转策略的行为钉子。这几条都是「安静地错」的类型：真机上看不出来，
 * 等到需要崩溃日志的那天才发现日志被多删了一代、或者导出的内容前后颠倒。
 */
class LogFileRotationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(dir: File, name: String, body: String) {
        File(dir, name).writeText(body)
    }

    private fun namesOf(files: List<File>) = files.map { it.name }

    @Test
    fun `rotate shifts every generation one step older and opens room for a new current`() {
        val dir = temp.newFolder()
        write(dir, LogFileRotation.CURRENT_NAME, "now")
        write(dir, LogFileRotation.generationName(1), "older")
        write(dir, LogFileRotation.generationName(2), "oldest")

        LogFileRotation.rotate(dir)

        assertFalse("当前文件已让位，等调用方重新打开", File(dir, LogFileRotation.CURRENT_NAME).exists())
        assertEquals("now", File(dir, LogFileRotation.generationName(1)).readText())
        assertEquals("older", File(dir, LogFileRotation.generationName(2)).readText())
        assertEquals("oldest", File(dir, LogFileRotation.generationName(3)).readText())
    }

    @Test
    fun `rotate drops only the oldest generation and keeps the retention bound`() {
        val dir = temp.newFolder()
        write(dir, LogFileRotation.CURRENT_NAME, "now")
        for (i in 1..LogFileRotation.KEEP_GENERATIONS) {
            write(dir, LogFileRotation.generationName(i), "gen$i")
        }

        LogFileRotation.rotate(dir)

        // 只有最老的那一代消失；文件总数不会随轮转次数增长。
        assertEquals("gen${LogFileRotation.KEEP_GENERATIONS - 1}",
            File(dir, LogFileRotation.generationName(LogFileRotation.KEEP_GENERATIONS)).readText())
        assertEquals(
            LogFileRotation.KEEP_GENERATIONS,
            dir.listFiles()!!.size,
        )
    }

    @Test
    fun `repeated rotation never exceeds the retention bound`() {
        val dir = temp.newFolder()
        repeat(10) { round ->
            write(dir, LogFileRotation.CURRENT_NAME, "round$round")
            LogFileRotation.rotate(dir)
            assertTrue(
                "第 $round 轮之后文件数超出保留上限",
                dir.listFiles()!!.size <= LogFileRotation.KEEP_GENERATIONS,
            )
        }
    }

    @Test
    fun `existingOldestFirst returns chronological order so the exported log reads top-down`() {
        val dir = temp.newFolder()
        write(dir, LogFileRotation.generationName(2), "oldest")
        write(dir, LogFileRotation.generationName(1), "older")
        write(dir, LogFileRotation.CURRENT_NAME, "now")

        assertEquals(
            listOf(
                LogFileRotation.generationName(2),
                LogFileRotation.generationName(1),
                LogFileRotation.CURRENT_NAME,
            ),
            namesOf(LogFileRotation.existingOldestFirst(dir)),
        )
    }

    @Test
    fun `existingOldestFirst skips empty files and missing generations`() {
        val dir = temp.newFolder()
        write(dir, LogFileRotation.generationName(2), "oldest")
        write(dir, LogFileRotation.CURRENT_NAME, "")     // 刚轮转完、还没写入的空文件

        assertEquals(
            listOf(LogFileRotation.generationName(2)),
            namesOf(LogFileRotation.existingOldestFirst(dir)),
        )
    }

    @Test
    fun `existingOldestFirst on a fresh install returns nothing rather than blowing up`() {
        assertEquals(emptyList<String>(), namesOf(LogFileRotation.existingOldestFirst(temp.newFolder())))
    }
}
