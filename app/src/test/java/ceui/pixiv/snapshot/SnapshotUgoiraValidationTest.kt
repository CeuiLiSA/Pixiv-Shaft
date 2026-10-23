package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.ObjectType
import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 动图快照(#1276)的完整性口径：p0 静图之外，还必须带一份读得回来的帧序列 ——
 * 导入 / 转正都靠 [SnapshotValidator] 把关，缺了帧的动图快照打开后只剩一张静图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SnapshotUgoiraValidationTest.TestApplication::class)
class SnapshotUgoiraValidationTest {

    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private val manifest = SnapshotManifest(
        snapshotId = "ugoira-snap",
        illustId = 1L,
        type = ObjectType.GIF,
        pageCount = 1,
        coverPath = "images/p0.jpg",
    )

    @Before
    fun setUp() {
        Shaft.sGson = Gson()
        dir = temp.newFolder(manifest.snapshotId)
        File(dir, SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(manifest))
        File(dir, SNAPSHOT_ILLUST_JSON).writeText(
            Shaft.sGson.toJson(Illust(id = 1L, type = ObjectType.GIF, page_count = 1))
        )
        File(dir, "images").mkdirs()
        File(dir, "images/p0.jpg").writeBytes(byteArrayOf(1))
    }

    @Test
    fun `rejects ugoira snapshot without frames`() {
        val error = runCatching { SnapshotValidator.validate(dir, manifest) }.exceptionOrNull()
        assertTrue(error is SnapshotException)
        assertTrue(error!!.message!!.contains("动图帧序列缺失"))
    }

    @Test
    fun `rejects ugoira snapshot whose delays do not match frames`() {
        writeFrames(frameCount = 3, delayCount = 2)
        val error = runCatching { SnapshotValidator.validate(dir, manifest) }.exceptionOrNull()
        assertTrue(error is SnapshotException)
    }

    @Test
    fun `accepts ugoira snapshot with readable frames`() {
        writeFrames(frameCount = 3, delayCount = 3)
        SnapshotValidator.validate(dir, manifest)
    }

    private fun writeFrames(frameCount: Int, delayCount: Int) {
        val ugoira = File(dir, SNAPSHOT_UGOIRA_DIR).apply { mkdirs() }
        repeat(frameCount) { File(ugoira, "%08d.jpg".format(it)).writeBytes(byteArrayOf(1)) }
        File(ugoira, "delays.txt").writeText(List(delayCount) { 40 }.joinToString("\n"))
    }
}
