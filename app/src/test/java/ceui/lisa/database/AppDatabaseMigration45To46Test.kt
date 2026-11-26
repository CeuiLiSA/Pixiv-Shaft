package ceui.lisa.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 覆盖 [AppDatabase.MIGRATION_45_46]：
 * 1. 存量关注行（contentType = 2）预览作品全空 / 全不可见时回填 `isVisible = 0`；
 * 2. 有可见预览作品、损坏 JSON、或插画/小说行不被误伤；
 * 3. 新增 `(shelfKey, isVisible, bookmarkSeq)` 复合索引。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppDatabaseMigration45To46Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(45) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(CREATE_BOOKMARK_MIRROR_TABLE_V45)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun `migration 45 to 46 marks empty-preview followings invalid and adds visibility index`() {
        // 1. 关注：空 illusts + 缺失 novels（典型的无作品/已失效关注）
        insertRow("2:0:100", 1L, contentType = 2, createDateMs = 0L, isVisible = 1,
            payload = """{"illusts":[],"user":{"id":1,"name":"Empty"}}""")
        // 2. 关注：空 illusts + 空 novels
        insertRow("2:1:100", 2L, contentType = 2, createDateMs = 0L, isVisible = 1,
            payload = """{"illusts":[],"novels":[],"user":{"id":2,"name":"EmptyBoth"}}""")
        // 3. 关注：预览作品全部 visible=false，且 v45 曾带出非零 createDateMs（不得因 createDateMs > 0 漏回填）
        insertRow("2:0:100", 3L, contentType = 2, createDateMs = 1_700_000_000_000L, isVisible = 1,
            payload = """{"illusts":[{"id":9,"visible":false}],"novels":[],"user":{"id":3,"name":"Hidden"}}""")
        // 3b. 关注：混合不可见与可见预览作品 → 仍应保持有效
        insertRow("2:0:100", 30L, contentType = 2, createDateMs = 1_700_000_000_000L, isVisible = 1,
            payload = """{"illusts":[{"id":9,"visible":false},{"id":12,"visible":true}],"novels":[],"user":{"id":30,"name":"Mixed"}}""")
        // 4. 关注：有可见作品但缺发布时间（createDateMs = 0 也不该误伤）
        insertRow("2:0:100", 4L, contentType = 2, createDateMs = 0L, isVisible = 1,
            payload = """{"illusts":[],"novels":[{"id":10,"title":"story"}],"user":{"id":4,"name":"UndatedNovel"}}""")
        // 5. 关注：正常有发布时间的活跃作者
        insertRow("2:0:100", 5L, contentType = 2, createDateMs = 1_700_000_000_000L, isVisible = 1,
            payload = """{"illusts":[{"id":11,"visible":true}],"user":{"id":5,"name":"Active"}}""")
        // 6. 关注：损坏 JSON（fail-safe，不崩也不误标失效）
        insertRow("2:0:100", 6L, contentType = 2, createDateMs = 0L, isVisible = 1,
            payload = "broken-json")
        // 7. 插画收藏（contentType = 0）：即使 createDateMs = 0 也不受关注回填影响
        insertRow("0:0:100", 7L, contentType = 0, createDateMs = 0L, isVisible = 1,
            payload = """{"id":7,"visible":true}""")
        insertRow("0:0:100", 8L, contentType = 0, createDateMs = 0L, isVisible = 0,
            payload = """{"id":8,"visible":false}""")

        AppDatabase.MIGRATION_45_46.migrate(db)

        assertEquals(0, readIsVisible("2:0:100", 1L))
        assertEquals(0, readIsVisible("2:1:100", 2L))
        assertEquals(0, readIsVisible("2:0:100", 3L))
        assertEquals(1, readIsVisible("2:0:100", 30L))
        assertEquals(1, readIsVisible("2:0:100", 4L))
        assertEquals(1, readIsVisible("2:0:100", 5L))
        assertEquals(1, readIsVisible("2:0:100", 6L))
        assertEquals(1, readIsVisible("0:0:100", 7L))
        assertEquals(0, readIsVisible("0:0:100", 8L))

        assertTrue(hasIndex("bookmark_mirror_table", "index_bookmark_mirror_table_shelfKey_isVisible_bookmarkSeq"))
    }

    private fun insertRow(
        shelfKey: String,
        targetId: Long,
        contentType: Int,
        createDateMs: Long,
        isVisible: Int,
        payload: String,
    ) {
        db.execSQL(
            """
            INSERT INTO bookmark_mirror_table (
                shelfKey, targetId, ownerUid, contentType, restrictCode, bookmarkSeq,
                payloadJson, title, authorId, authorName, workType, pageCount, width, height,
                aspectRatio, orientation, totalBookmarks, totalView, textLength, createDateMs,
                aiType, xRestrict, sanityLevel, isVisible, isMuted, seriesId, tagCount,
                searchText, syncedAt, generation
            ) VALUES (?, ?, 100, ?, 0, ?, ?, '', ?, '', 'user', 0, 0, 0, 0, 0, 0, 0, 0, ?, 0, 0, 0, ?, 0, 0, 0, '', 0, 1)
            """.trimIndent(),
            arrayOf<Any>(shelfKey, targetId, contentType, -targetId, payload, targetId, createDateMs, isVisible),
        )
    }

    private fun readIsVisible(shelfKey: String, targetId: Long): Int =
        db.query(
            "SELECT isVisible FROM bookmark_mirror_table WHERE shelfKey = ? AND targetId = ?",
            arrayOf<Any>(shelfKey, targetId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun hasIndex(table: String, index: String): Boolean =
        db.query("PRAGMA index_list('$table')").use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == index) return true
            }
            false
        }

    private companion object {
        const val CREATE_BOOKMARK_MIRROR_TABLE_V45 =
            "CREATE TABLE IF NOT EXISTS `bookmark_mirror_table` (" +
                "`shelfKey` TEXT NOT NULL, `targetId` INTEGER NOT NULL, `ownerUid` INTEGER NOT NULL, " +
                "`contentType` INTEGER NOT NULL, `restrictCode` INTEGER NOT NULL, `bookmarkSeq` INTEGER NOT NULL, " +
                "`payloadJson` TEXT NOT NULL, `title` TEXT NOT NULL, `authorId` INTEGER NOT NULL, " +
                "`authorName` TEXT NOT NULL, `workType` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, " +
                "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `aspectRatio` REAL NOT NULL, " +
                "`orientation` INTEGER NOT NULL, `totalBookmarks` INTEGER NOT NULL, `totalView` INTEGER NOT NULL, " +
                "`textLength` INTEGER NOT NULL, `createDateMs` INTEGER NOT NULL, `aiType` INTEGER NOT NULL, " +
                "`xRestrict` INTEGER NOT NULL, `sanityLevel` INTEGER NOT NULL, `isVisible` INTEGER NOT NULL, " +
                "`isMuted` INTEGER NOT NULL, `seriesId` INTEGER NOT NULL, `tagCount` INTEGER NOT NULL, " +
                "`searchText` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, `generation` INTEGER NOT NULL, " +
                "PRIMARY KEY(`shelfKey`, `targetId`))"
    }
}
