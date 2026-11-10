package ceui.pixiv.snapshot

import android.content.ContextWrapper
import ceui.lisa.activities.Shaft
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
@Config(sdk = [35], application = AutoSnapshotQuotaEvictionTest.TestApplication::class)
class AutoSnapshotQuotaEvictionTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @get:Rule val temp = TemporaryFolder()

    @Before
    fun setUp() {
        Shaft.sGson = Gson()
    }

    @Test
    fun oversizedNewSnapshotIsRemovedBeforeOlderSnapshots() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp.root
        }

        fun add(id: String, size: Long, createdAt: Long) {
            val dir = SnapshotRepository.createSnapshotDir(context, id)
            val manifest = AutoSnapshotManifest(
                snapshotId = id,
                illustId = createdAt,
                createdAt = createdAt,
                totalSize = size,
                fileCount = 1,
            )
            File(dir, AUTO_SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(manifest))
        }

        add("old", size = 40, createdAt = 1)
        add("huge", size = 150, createdAt = 2)

        AutoSnapshotRepository.enforceAutoQuota(context, maxBytes = 100)

        assertTrue(File(SnapshotRepository.dir(context, "old"), AUTO_SNAPSHOT_MANIFEST).isFile)
        assertFalse(SnapshotRepository.dir(context, "huge").exists())
    }
}