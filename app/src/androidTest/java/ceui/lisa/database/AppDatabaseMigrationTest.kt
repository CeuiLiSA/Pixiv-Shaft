package ceui.lisa.database

import androidx.room.testing.MigrationTestHelper
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
        db.close()
    }

    private companion object {
        const val DB_NAME = "app-database-migration-test.db"
        const val EARLIEST_SCHEMA_VERSION = 25
    }
}
