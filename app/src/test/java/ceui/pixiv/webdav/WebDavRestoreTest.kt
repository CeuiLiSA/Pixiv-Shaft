package ceui.pixiv.webdav

import android.app.Application
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.UserEntity
import ceui.lisa.utils.BackupUtils
import ceui.lisa.utils.Local
import ceui.lisa.utils.Settings
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebDavRestoreTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sGson = Gson()
        Shaft.sPreferences = app.getSharedPreferences("webdav-restore-test", 0)
        Shaft.sPreferences.edit().clear().commit()
        Shaft.sSettings = Settings().apply {
            aiTranslateApiKey = "local-ai-key"
            aria2RpcSecret = "local-rpc-secret"
            isCloudHistoryConsentShown = false
            moonAppliedVersions = mapOf("123" to 7)
            cloudHistoryBackfillDoneUid = 123L
        }
    }

    @After
    fun tearDown() {
        AppDatabase.destroyInstance()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", null)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", null)
        db.close()
    }

    private fun export(includeCredentials: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        BackupUtils.writeBackup(app, false, includeCredentials, out)
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun assertLocalFieldsKept() {
        val restored = Shaft.sSettings
        assertEquals("local-ai-key", restored.aiTranslateApiKey)
        assertEquals("local-rpc-secret", restored.aria2RpcSecret)
        assertFalse(restored.isCloudHistoryConsentShown)
        assertEquals(mapOf("123" to 7), restored.moonAppliedVersions)
        assertEquals(0L, restored.cloudHistoryBackfillDoneUid)
        assertEquals("local-ai-key", Local.getSettings().aiTranslateApiKey)
    }

    @Test
    fun `cloud export omits credentials without changing local settings`() {
        db.downloadDao().insertUser(UserEntity().apply {
            userID = 123L
            userGson = """{"access_token":"access-secret","refresh_token":"refresh-secret"}"""
        })

        val bytes = export(false)
        val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        assertFalse(json.has("userEntityList"))
        assertEquals("", json.getAsJsonObject("settings").get("aiTranslateApiKey").asString)
        assertEquals("", json.getAsJsonObject("settings").get("aria2RpcSecret").asString)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("refresh-secret"))
        assertEquals("local-ai-key", Shaft.sSettings.aiTranslateApiKey)
        assertEquals("local-rpc-secret", Shaft.sSettings.aria2RpcSecret)
    }

    @Test
    fun `downloaded cloud backup preserves device fields through the local restore entry`() {
        val bytes = export(false)
        assertNotNull(BackupUtils.restoreBackupEntity(app, gzip(bytes).inputStream()))
        assertLocalFieldsKept()
    }

    @Test
    fun `uncompressed cloud export also preserves device fields`() {
        assertNotNull(BackupUtils.restoreBackupEntity(app, export(false).inputStream()))
        assertLocalFieldsKept()
    }

    @Test
    fun `legacy WebDAV gzip without metadata preserves device fields`() {
        val json = """{"settings":{"aiTranslateApiKey":"","aria2RpcSecret":"","cloudHistoryConsentShown":true,"moonAppliedVersions":{"456":10},"cloudHistoryBackfillDoneUid":456}}"""
        assertNotNull(BackupUtils.restoreBackupEntity(app, gzip(json.toByteArray()).inputStream()))
        assertLocalFieldsKept()
    }

    @Test
    fun `ordinary local backup retains its credential replacement behavior`() {
        val json = """{"settings":{"aiTranslateApiKey":"","aria2RpcSecret":""},"userEntityList":[]}"""
        assertNotNull(BackupUtils.restoreBackupEntity(app, json.byteInputStream()))
        assertEquals("", Shaft.sSettings.aiTranslateApiKey)
        assertEquals("", Shaft.sSettings.aria2RpcSecret)
    }

    @Test
    fun `gzip local backup with credentials keeps its existing replacement behavior`() {
        val bytes = export(true)
        Shaft.sSettings.aiTranslateApiKey = "new-local-key"
        assertNotNull(BackupUtils.restoreBackupEntity(app, gzip(bytes).inputStream()))
        assertEquals("local-ai-key", Shaft.sSettings.aiTranslateApiKey)
    }

    @Test
    fun `missing gzip trailer cannot apply settings or report restore success`() {
        val json = """{"settings":{"aiTranslateApiKey":"incoming"},"padding":"${"x".repeat(40_000)}"}"""
        val bytes = gzip(json.toByteArray()).dropLast(8).toByteArray()
        assertNull(BackupUtils.restoreBackupEntity(app, bytes.inputStream()))
        assertEquals("local-ai-key", Shaft.sSettings.aiTranslateApiKey)
    }
}
