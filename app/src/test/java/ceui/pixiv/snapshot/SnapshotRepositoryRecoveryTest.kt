package ceui.pixiv.snapshot

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SnapshotRepositoryRecoveryTest.TestApplication::class)
class SnapshotRepositoryRecoveryTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private val id = "recovery-test"
    private val illustId = 1351L
    private val manifest get() = SnapshotManifest(
        snapshotId = id, illustId = illustId, createdAt = 123L, coverPath = "images/last.jpg",
    )

    @Before fun setUp() {
        Shaft.sGson = Gson()
        val cache = temp.newFolder("cache")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp.root
            override fun getCacheDir(): File = cache
            override fun getApplicationContext(): Context = this
        }
        SnapshotIndex.onSnapshotDeleted(id)
    }

    @After fun tearDown() {
        SnapshotIndex.onSnapshotDeleted(id)
        SnapshotRuntimeCache.remove(id)
    }

    @Test fun `file paths present with a truncated final image restores the complete backup`() = runBlocking {
        fixture(".backup_${id}_1", "complete old image")
        val target = fixture(id, "truncated")
        // 清单、元数据和所有资源路径均存在，原校验确实会接受这份未写完的目标。
        SnapshotValidator.validate(target, manifest)
        failImportAfterRecovery()
        assertEquals("complete old image", File(target, "images/last.jpg").readText())
        val displaced = SnapshotRepository.root(context).listFiles()!!.single { it.name.startsWith(".incomplete_") }
        assertEquals("truncated", File(displaced, "images/last.jpg").readText())
    }

    @Test fun `recovery after the startup scan reindexes the snapshot even when the next import fails`() = runBlocking {
        fixture(".backup_${id}_1", "complete old image")
        val persisted = mapOf(id to SnapshotIndexRecord(illustId, SnapshotIndex.Entry(id, false, 123L)))
        assertTrue(scanSnapshotIndex(SnapshotRepository.root(context), persisted).isEmpty())
        val revision = SnapshotIndex.revision
        SnapshotIndex.ensureLoaded(context)
        withTimeout(5_000) {
            while (SnapshotIndex.revision == revision) delay(10)
        }
        assertNull(SnapshotIndex.entryFor(illustId))
        SnapshotRuntimeCache.put(id, SnapshotViewerData(
            SnapshotRepository.dir(context, id), manifest, Illust(id = illustId), emptyMap(), null,
        ))
        failImportAfterRecovery()
        assertEquals(SnapshotIndex.Entry(id, false, 123L), SnapshotIndex.entryFor(illustId))
        assertNull(SnapshotRuntimeCache.get(id))
        assertTrue(SnapshotRepository.dir(context, id).isDirectory)
    }

    @Test fun `committed replacement is reindexed before its obsolete backup is removed`() = runBlocking {
        val backup = SnapshotImportRecovery.markCommitted(fixture(".backup_${id}_1", "old"))
        val target = fixture(id, "complete new image")
        assertNull(SnapshotIndex.entryFor(illustId))
        failImportAfterRecovery()
        assertEquals("complete new image", File(target, "images/last.jpg").readText())
        assertEquals(SnapshotIndex.Entry(id, false, 123L), SnapshotIndex.entryFor(illustId))
        assertFalse(backup.exists())
    }

    private suspend fun failImportAfterRecovery() {
        val invalid = temp.newFile().also { it.writeText("not a snapshot zip") }
        assertTrue(runCatching { SnapshotRepository.import(context, Uri.fromFile(invalid)) }.exceptionOrNull() is SnapshotException)
    }

    private fun fixture(name: String, lastImage: String): File {
        val dir = File(SnapshotRepository.root(context), name).apply { mkdirs() }
        File(dir, SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(manifest))
        File(dir, SNAPSHOT_ILLUST_JSON).writeText(Shaft.sGson.toJson(Illust(id = illustId, page_count = 1)))
        File(dir, "images").mkdirs()
        File(dir, "images/first.jpg").writeText("complete first image")
        File(dir, "images/last.jpg").writeText(lastImage)
        File(dir, SNAPSHOT_ASSETS_JSON).writeText(Shaft.sGson.toJson(SnapshotAssets(mapOf(
            "first" to "images/first.jpg", "last" to "images/last.jpg",
        ))))
        return dir
    }
}
