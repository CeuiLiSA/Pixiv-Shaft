package ceui.pixiv.db.mirror

import android.app.Application
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.User
import ceui.pixiv.api.model.UserPreview
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AiFollowedAuthorExemptionTest {
    private lateinit var db: AppDatabase
    private var previousGson: Gson? = null
    private val publicShelf = BookmarkShelf(1L, MirrorContentType.USER, MirrorRestrict.PUBLIC)
    private val privateShelf = BookmarkShelf(1L, MirrorContentType.USER, MirrorRestrict.PRIVATE)
    private val dao get() = db.bookmarkMirrorDao()

    @Before
    fun setUp() {
        previousGson = Shaft.sGson
        Shaft.sGson = Gson()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        Shaft.sGson = previousGson
    }

    @Test
    fun `partial public or private backfill never supplies exemption IDs`() {
        insertUser(publicShelf, 100L)
        insertUser(privateShelf, 200L)
        assertTrue(followedIds().isEmpty())
        dao.upsertState(state(publicShelf, ready = true))
        dao.upsertState(state(privateShelf, ready = false))
        assertTrue(followedIds().isEmpty())
        dao.upsertState(state(publicShelf, ready = false))
        dao.upsertState(state(privateShelf, ready = true))
        assertTrue(followedIds().isEmpty())
    }

    @Test
    fun `ready shelves combine public and private follows and isolate accounts`() {
        dao.upsertState(state(publicShelf, ready = true))
        dao.upsertState(state(privateShelf, ready = true))
        insertUser(publicShelf, 100L)
        insertUser(privateShelf, 200L)
        insertUser(BookmarkShelf(2L, MirrorContentType.USER, MirrorRestrict.PUBLIC), 300L)
        assertEquals(setOf(100L, 200L), followedIds())
        // An empty, fully synchronized private shelf is also trustworthy.
        dao.clearShelf(privateShelf.key)
        assertEquals(setOf(100L), followedIds())
    }

    @Test
    fun `dropping or rebuilding either shelf revokes readiness even if rows remain`() {
        dao.upsertState(state(publicShelf, ready = true))
        dao.upsertState(state(privateShelf, ready = true))
        insertUser(publicShelf, 100L)
        assertEquals(setOf(100L), followedIds())
        dao.deleteState(privateShelf.key)
        assertTrue(followedIds().isEmpty())
        dao.upsertState(state(privateShelf, ready = false))
        assertTrue(followedIds().isEmpty())
        dao.upsertState(state(privateShelf, ready = true).copy(phase = MirrorPhase.RESWEEPING))
        assertEquals(setOf(100L), followedIds())
    }

    private fun followedIds() = dao.followedUserIds(publicShelf.key, privateShelf.key).toSet()

    private fun insertUser(shelf: BookmarkShelf, id: Long) {
        val row = BookmarkMirrorMapper.fromUserPreview(
            shelf, UserPreview(user = User(id = id)), 0L, 1, 1L,
        )
        dao.writePage(listOf(row.row), row.tags)
    }

    private fun state(shelf: BookmarkShelf, ready: Boolean) = BookmarkMirrorStateEntity(
        shelfKey = shelf.key, ownerUid = shelf.ownerUid, contentType = shelf.contentType.code,
        restrictCode = shelf.restrict.code, phase = if (ready) MirrorPhase.SYNCED else MirrorPhase.BACKFILLING,
        nextUrl = null, generation = 1, nextBackfillSeq = 0L, headSeqCursor = 0L,
        headBlockCeiling = 0L, pagesThisRun = 0, itemsThisRun = 0,
        firstCompletedAt = if (ready) 1L else 0L, lastSyncedAt = 0L, lastFullSweepAt = 0L,
        lastErrorAt = 0L, lastError = null, consecutiveFailures = 0, cooldownUntil = 0L, updatedAt = 1L,
    )
}
