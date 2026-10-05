package ceui.pixiv.db.queue

import android.app.Application
import androidx.room.Room
import ceui.lisa.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 队列调度顺序的回归测试。
 *
 * `nextByStatus` 是 `ORDER BY seq ASC`，所以"失败行回退 PENDING 后 seq 不动"会让下一轮
 * 立刻又拿到同一行 —— 表现为逮着同一个作品反复重试，后面的作品一直轮不到。这里的
 * `retryPending`（无进展失败 → 推队尾）与 `bumpRetry`（有进展的续传 → 保持原位优先续完）
 * 就是这条不变量，改动它们必须让本测试翻。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadQueueDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DownloadQueueDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.downloadQueueDao()
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun enqueue(id: Long, seq: Long, status: String = QueueStatus.PENDING) {
        dao.insertAll(listOf(DownloadQueueEntity(id = id, illustId = id, seq = seq, status = status)))
    }

    @Test fun `retryPending 把失败行推到队尾，下一轮不再拿同一行`() = runBlocking {
        enqueue(1, 1); enqueue(2, 2); enqueue(3, 3)
        assertEquals("队首应是 id=1", 1L, dao.nextByStatus(QueueStatus.PENDING)!!.id)

        val tail = (dao.maxSeq() ?: 0L) + 1
        dao.retryPending(1, "boom", tail)

        val row = dao.getById(1)!!
        assertEquals(QueueStatus.PENDING, row.status)
        assertEquals(1, row.retryCount)
        assertEquals("boom", row.errorMsg)
        assertEquals("seq 必须推到队尾", tail, row.seq)
        assertEquals("下一轮应让位给 id=2", 2L, dao.nextByStatus(QueueStatus.PENDING)!!.id)
    }

    @Test fun `bumpRetry 不动 seq —— 有进展的续传保持原位优先续完`() = runBlocking {
        enqueue(1, 1); enqueue(2, 2)

        dao.bumpRetry(1)
        dao.updateStatus(1, QueueStatus.PENDING)

        assertEquals(1L, dao.getById(1)!!.seq)
        assertEquals(1, dao.getById(1)!!.retryCount)
        assertEquals("续传不该被推走", 1L, dao.nextByStatus(QueueStatus.PENDING)!!.id)
    }

    @Test fun `resequence 只改 seq，不动状态`() = runBlocking {
        enqueue(1, 1)
        dao.updateStatus(1, QueueStatus.DOWNLOADING)

        dao.resequence(1, 99)

        val row = dao.getById(1)!!
        assertEquals(99L, row.seq)
        assertEquals(QueueStatus.DOWNLOADING, row.status)
    }

    @Test fun `retryPending 后整队顺序仍严格按 seq 升序`() = runBlocking {
        enqueue(1, 1); enqueue(2, 2); enqueue(3, 3)
        dao.retryPending(2, null, (dao.maxSeq() ?: 0L) + 1)

        val order = buildList {
            while (true) {
                val next = dao.nextByStatus(QueueStatus.PENDING) ?: break
                add(next.id)
                dao.updateStatus(next.id, QueueStatus.SUCCESS)
            }
        }
        assertEquals("id=2 被推到队尾，其余保持原序", listOf(1L, 3L, 2L), order)
    }
}
