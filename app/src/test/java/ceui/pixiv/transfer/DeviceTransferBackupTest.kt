package ceui.pixiv.transfer

import android.app.Application
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.database.SearchEntity
import ceui.lisa.database.UserEntity
import ceui.lisa.utils.BackupUtils
import ceui.lisa.utils.Settings
import ceui.pixiv.db.EntityType
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.synonym.SynonymDictBackup
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
class DeviceTransferBackupTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sGson = Gson()
        Shaft.sPreferences = app.getSharedPreferences("device-transfer-test", 0)
        useFreshDatabase()
    }

    @After
    fun tearDown() {
        AppDatabase.destroyInstance()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", null)
    }

    @Test
    fun `migration merges records and preserves fresh local rows and sessions`() {
        val data = oldPhoneBackup()
        useFreshDatabase()
        db.downloadDao().insertUser(account(42, "new-session"))
        db.downloadDao().insert(history(1, 100))
        db.searchDao().insert(search(pinned = true, time = 1))
        Shaft.sSettings = Settings().apply {
            rootPathUri = "content://new-phone"
            isCloudHistorySync = false
            isCloudHistoryConsentShown = true
            cloudHistoryBackfillDoneUid = 42
        }

        repeat(2) { BackupUtils.restoreTransferBackup(app, data.inputStream(), 42) {} }

        assertEquals("old-key", Shaft.sSettings.aiTranslateApiKey)
        assertEquals("content://new-phone", Shaft.sSettings.rootPathUri)
        assertEquals(false, Shaft.sSettings.isCloudHistorySync)
        assertEquals(true, Shaft.sSettings.isCloudHistoryConsentShown)
        assertEquals(0L, Shaft.sSettings.cloudHistoryBackfillDoneUid)
        assertEquals("new-session", db.downloadDao().getUserByUid(42).userGson)
        assertEquals("second-account", db.downloadDao().getUserByUid(43).userGson)
        assertEquals(2, db.downloadDao().viewHistoryCount)
        assertEquals(100L, db.downloadDao().getViewHistoryTimes(listOf(1)).single().time)
        assertEquals(true, db.searchDao().getSearchEntity(20).isPinned)
        assertEquals(listOf(8L), db.generalDao().getAllIdsByRecordType(RecordType.WATCH_LATER))
        assertEquals(listOf(9L), db.generalDao().getAllIdsByRecordType(RecordType.VIEW_USER_HISTORY))
        val dict = db.synonymDao().getAllWithSynonyms().single()
        assertEquals("EVA", dict.target.name)
        assertEquals("Evangelion", dict.synonyms.single().name)
    }

    @Test
    fun `migration merges pinned users by timestamp and rejects spoofed record types`() {
        db.generalDao().insert(GeneralEntity(50, """{"name":"old-stale"}""", EntityType.USER, RecordType.PINNED_USER, 100))
        db.generalDao().insert(GeneralEntity(51, """{"name":"old-fresh"}""", EntityType.USER, RecordType.PINNED_USER, 300))
        val data = oldPhoneBackup()
        useFreshDatabase()
        db.generalDao().insert(GeneralEntity(50, """{"name":"local-fresh"}""", EntityType.USER, RecordType.PINNED_USER, 200))
        db.generalDao().insert(GeneralEntity(51, """{"name":"local-stale"}""", EntityType.USER, RecordType.PINNED_USER, 150))

        BackupUtils.restoreTransferBackup(app, data.inputStream(), 42) {}

        assertEquals(
            """{"name":"local-fresh"}""",
            db.generalDao().getByRecordTypeAndId(RecordType.PINNED_USER, 50)?.json,
        )
        assertEquals(
            """{"name":"old-fresh"}""",
            db.generalDao().getByRecordTypeAndId(RecordType.PINNED_USER, 51)?.json,
        )

        useFreshDatabase()
        val tampered = """
            {
              "settings": {},
              "pinnedUserEntityList": [{"id": 77, "json": "{}", "entityType": 1, "recordType": ${RecordType.WATCH_LATER}, "updatedTime": 500}],
              "transferRecords": {"records": [], "synonyms": "{\"version\":1,\"targets\":[]}"}
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            BackupUtils.restoreTransferBackup(app, tampered.byteInputStream(), 42) {}
        }
        assertEquals(emptyList<GeneralEntity>(), db.generalDao().getAll())
    }

    @Test
    fun `invalid gzip trailer rolls back data already parsed and preserves settings`() {
        val data = oldPhoneBackup()
        useFreshDatabase()
        Shaft.sSettings = Settings().apply { aiTranslateApiKey = "local-key" }

        assertThrows(Exception::class.java) {
            BackupUtils.restoreTransferBackup(app, data.copyOf(data.size - 4).inputStream(), 42) {}
        }

        assertEquals(0, db.downloadDao().viewHistoryCount)
        assertEquals(emptyList<GeneralEntity>(), db.generalDao().getAll())
        assertEquals(0, db.synonymDao().getAllWithSynonyms().size)
        assertNull(db.downloadDao().getUserByUid(43))
        assertEquals("local-key", Shaft.sSettings.aiTranslateApiKey)
    }

    @Test
    fun `legacy restore refreshes committed mutes even when a later table fails`() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_feature BEFORE INSERT ON feature_table " +
                "BEGIN SELECT RAISE(ABORT, 'injected write failure'); END",
        )
        val revision = IllustMuteStore.revision
        val json = """
            {"muteEntityList":[{"id":42,"type":1}],"featureEntityList":[{"uuid":"feature"}]}
        """.trimIndent()

        assertNull(BackupUtils.restoreBackupEntity(app, json.byteInputStream()))
        assertEquals(listOf(42L), db.searchDao().getMutedWorkIds(1))
        assertEquals(revision + 1, IllustMuteStore.revision)
    }

    private fun oldPhoneBackup(): ByteArray {
        Shaft.sSettings = Settings().apply {
            aiTranslateApiKey = "old-key"
            isCloudHistorySync = true
        }
        db.downloadDao().insertUser(account(42, "old-session"))
        db.downloadDao().insertUser(account(43, "second-account"))
        db.downloadDao().insert(history(1, 10))
        db.downloadDao().insert(history(2, 11))
        db.searchDao().insert(search(pinned = false, time = 90))
        db.generalDao().insert(GeneralEntity(8, "{}", EntityType.ILLUST, RecordType.WATCH_LATER))
        db.generalDao().insert(GeneralEntity(9, "{}", EntityType.USER, RecordType.VIEW_USER_HISTORY))
        SynonymDictBackup.importFromJson(app, """{"targets":[{"name":"EVA","synonyms":[{"name":"Evangelion"}]}]}""")
        return ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { BackupUtils.writeTransferBackup(app, it) {} }
        }.toByteArray()
    }

    private fun useFreshDatabase() {
        AppDatabase.destroyInstance()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
    }

    private fun account(uid: Long, session: String) = UserEntity().apply {
        userID = uid
        userGson = session
    }

    private fun history(id: Long, timestamp: Long) = IllustHistoryEntity().apply {
        illustID = id
        illustJson = "{}"
        time = timestamp
    }

    private fun search(pinned: Boolean, time: Long) = SearchEntity().apply {
        id = 20
        keyword = "example"
        searchTime = time
        isPinned = pinned
    }
}
