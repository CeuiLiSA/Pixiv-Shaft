package ceui.lisa.utils

import android.app.Application
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.EntityType
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * 置顶作者（general_table 里 recordType=PINNED_USER 的行）进出备份的往返：
 * 导出要把它写进备份文件，还原要把行灌回 general_table，旧备份缺该字段时不能
 * 清掉本机已有的置顶。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupPinnedUserTest {

    private lateinit var db: AppDatabase
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sGson = Gson()
        Shaft.sSettings = Settings()
    }

    @After
    fun tearDown() {
        AppDatabase.destroyInstance()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", null)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", null)
        db.close()
    }

    private fun pinnedRow(id: Long, updatedTime: Long = 1000L) = GeneralEntity(
        id = id,
        json = """{"id":$id,"name":"artist-$id"}""",
        entityType = EntityType.USER,
        recordType = RecordType.PINNED_USER,
        updatedTime = updatedTime,
    )

    private fun pinnedIds(): List<Long> =
        db.generalDao().getByRecordType(RecordType.PINNED_USER, 0, Int.MAX_VALUE).map { it.id }

    @Test
    fun `export writes pinned authors into the backup file`() {
        db.generalDao().insert(pinnedRow(11L))
        val file = File.createTempFile("shaft-backup", ".json")
        try {
            BackupUtils.writeBackupToFile(app, false, file)
            val json = file.readText()
            assertTrue(json.contains("pinnedUserEntityList"))
            assertTrue(json.contains("artist-11"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `restore reinserts pinned authors from the backup`() {
        val json = """{"pinnedUserEntityList":[""" +
            """{"id":11,"json":"{\"id\":11,\"name\":\"a\"}","entityType":2,"recordType":9,"updatedTime":1000},""" +
            """{"id":22,"json":"{\"id\":22,\"name\":\"b\"}","entityType":2,"recordType":9,"updatedTime":2000}""" +
            """]}"""
        assertTrue(BackupUtils.restoreBackups(app, json))
        assertEquals(listOf(11L, 22L), pinnedIds().sorted())
        assertEquals(
            RecordType.PINNED_USER,
            db.generalDao().getByRecordTypeAndId(RecordType.PINNED_USER, 11L)?.recordType,
        )
    }

    @Test
    fun `backup without the pinned field keeps the local pins`() {
        db.generalDao().insert(pinnedRow(11L))
        assertTrue(BackupUtils.restoreBackups(app, """{"settings":null}"""))
        assertEquals(listOf(11L), pinnedIds())
    }
}
