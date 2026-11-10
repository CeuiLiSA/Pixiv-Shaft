package ceui.lisa.update

import android.app.Application
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [ApkDownloadManagerRestoreTest.MemoryMMKV::class])
class ApkDownloadManagerRestoreTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var downloads: ApkDownloadManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        MemoryMMKV.values.clear()
        downloads = ApkDownloadManager(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        downloads.apkFile()?.delete()
        MemoryMMKV.values.clear()
    }

    @Test
    fun `completed record waits for background validation before becoming installable`() = runTest {
        val apk = requireNotNull(downloads.apkFile())
        apk.parentFile?.mkdirs()
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("manifest".toByteArray())
            zip.closeEntry()
        }
        saveCompletedRecord(apk.length())

        downloads.install()
        assertEquals(ApkDownloadManager.Phase.MERGING, downloads.state.value.phase)

        val state = downloads.state.first { it.phase != ApkDownloadManager.Phase.MERGING }
        assertEquals(ApkDownloadManager.Phase.COMPLETED, state.phase)
        assertEquals(apk.length(), state.downloadedBytes)
        assertTrue(apk.exists())
        assertTrue(MemoryMMKV.values.containsKey(RECORD_KEY))
    }

    @Test
    fun `corrupt completed record clears the apk and record after background validation`() = runTest {
        val apk = requireNotNull(downloads.apkFile())
        apk.parentFile?.mkdirs()
        apk.writeBytes(ByteArray(128))
        saveCompletedRecord(apk.length())

        downloads.install()
        assertEquals(ApkDownloadManager.Phase.MERGING, downloads.state.value.phase)

        val state = downloads.state.first { it.phase != ApkDownloadManager.Phase.MERGING }
        assertEquals(ApkDownloadManager.Phase.IDLE, state.phase)
        assertFalse(apk.exists())
        assertFalse(MemoryMMKV.values.containsKey(RECORD_KEY))
    }

    private fun saveCompletedRecord(length: Long) {
        MemoryMMKV.values[RECORD_KEY] = """
            {"tag":"v9999.0.0","versionName":"9999.0.0","status":"COMPLETED",
             "totalBytes":$length,"segments":[],"bannerPending":false}
        """.trimIndent()
    }

    @Implements(MMKV::class, isInAndroidSdk = false)
    class MemoryMMKV {
        @Implementation
        fun decodeLong(key: String, defaultValue: Long): Long = defaultValue

        @Implementation
        fun decodeString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue

        @Implementation
        fun removeValueForKey(key: String) {
            values.remove(key)
        }

        companion object {
            val values = mutableMapOf<String, String>()

            @JvmStatic
            @Implementation
            fun defaultMMKV(): MMKV = ReflectionHelpers.callConstructor(
                MMKV::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L),
            )
        }
    }

    private companion object {
        const val RECORD_KEY = "update_apk_download_v2"
    }
}
