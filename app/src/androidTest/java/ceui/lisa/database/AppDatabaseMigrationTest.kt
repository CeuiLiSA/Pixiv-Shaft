package ceui.lisa.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the complete main-database migration chain covered by the committed schemas. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrateEarliestCommittedSchemaToLatest() {
        helper.createDatabase(DB_NAME, EARLIEST_SCHEMA_VERSION).use { db ->
            db.execSQL(
                "INSERT INTO search_table (id, keyword, searchTime, searchType, pinned) " +
                    "VALUES (42, 'migration-sentinel', 1234, 1, 1)",
            )
            // v44 只加索引，不动数据。这里放一条下载记录当「迁移没把行删掉」的哨兵
            // （v25 的 illust_download_table 还没有 illustId / page 列，只填老列）。
            db.execSQL(
                "INSERT INTO illust_download_table (fileName, filePath, illustGson, downloadTime) " +
                    "VALUES ('migration-sentinel.png', '/x', '{}', 5678)",
            )
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            AppDatabase.VERSION,
            true,
            *AppDatabase.migrations(),
        )

        db.query(
            "SELECT keyword, searchTime, searchType, pinned, previewIllustsJson " +
                "FROM search_table WHERE id = 42",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("migration-sentinel", cursor.getString(0))
            assertEquals(1234L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
            assertNull(cursor.getString(4))
        }

        db.query(
            "SELECT downloadTime FROM illust_download_table " +
                "WHERE fileName = 'migration-sentinel.png'",
        ).use { cursor ->
            assertTrue("v44 不该动数据，但下载记录没了", cursor.moveToFirst())
            assertEquals(5678L, cursor.getLong(0))
        }

        // v44 的六条索引必须真的建出来了。Room 的 onValidateSchema 也会查一遍，但那条失败信息
        // 只说「哪张表不对」；这里逐条点名，索引名写错时能立刻知道是哪一个。
        V44_INDICES.forEach { (table, index) ->
            assertTrue("缺少索引 $index（表 $table）", hasIndex(db, table, index))
        }

        db.close()
    }

    private fun hasIndex(db: SupportSQLiteDatabase, table: String, index: String): Boolean =
        db.query("PRAGMA index_list('$table')").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == index) return true
            }
            false
        }

    private companion object {
        const val DB_NAME = "app-database-migration-test.db"
        const val EARLIEST_SCHEMA_VERSION = 25

        /** 与 MIGRATION_43_44 里的 CREATE INDEX 一一对应。 */
        val V44_INDICES = listOf(
            "tag_mute_table" to "index_tag_mute_table_type_searchTime_id",
            "search_table" to "index_search_table_pinned_searchTime",
            "feature_table" to "index_feature_table_dateTime",
            "illust_table" to "index_illust_table_time",
            "general_table" to "index_general_table_recordType_updatedTime_id",
            "illust_download_table" to "index_illust_download_table_downloadTime",
        )
    }
}
