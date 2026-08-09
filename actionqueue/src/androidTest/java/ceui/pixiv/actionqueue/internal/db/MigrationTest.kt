package ceui.pixiv.actionqueue.internal.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 → v2 迁移的真机验证。
 *
 * 这个库刻意没开 `fallbackToDestructiveMigration`（躺在里面的是用户点过但还没发出去的
 * 收藏），所以每条 Migration 都是手写的，而 Room 会在打开库时逐字校验列和索引定义 ——
 * 少建一个索引、类型写错，线上表现是启动即 IllegalStateException。这条用例把迁移在真
 * SQLite 上跑一遍并让 Room 自己做校验（`validateMigration = true`）。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ActionQueueDatabase::class.java,
    )

    @Test
    fun migrate1To2() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO queued_action " +
                    "(type, dedupeKey, payload, gapMs, status, attempt, notBefore, createdAt, lastError) " +
                    "VALUES ('illust_bookmark', 'illust_bookmark:1', '{}', 0, 'PENDING', 0, 0, 1, NULL)"
            )
        }

        // validateMigration = true：Room 拿导出的 2.json 逐列逐索引比对迁移后的真实 schema。
        val db = helper.runMigrationsAndValidate(DB_NAME, 2, true, ActionQueueDatabase.MIGRATION_1_2)

        // v1 的行没有账号归属，留着只会重放到错误的账号上，迁移里一律清掉（见 MIGRATION_1_2）。
        db.query("SELECT COUNT(*) FROM queued_action").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // 新的 owner 列可写，queue_meta 可用。
        db.execSQL(
            "INSERT INTO queued_action " +
                "(type, dedupeKey, payload, gapMs, status, attempt, notBefore, createdAt, lastError, owner) " +
                "VALUES ('illust_bookmark', 'illust_bookmark:2', '{}', 0, 'PENDING', 0, 0, 2, NULL, '42')"
        )
        db.execSQL("INSERT INTO queue_meta (`key`, `value`) VALUES ('cooldown_until_ms', 12345)")
        db.query("SELECT `value` FROM queue_meta WHERE `key` = 'cooldown_until_ms'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(12345L, c.getLong(0))
        }
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
