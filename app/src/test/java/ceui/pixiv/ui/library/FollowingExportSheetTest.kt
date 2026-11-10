package ceui.pixiv.ui.library

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.descendants
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.room.withTransaction
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Settings
import ceui.loxia.User
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.db.mirror.BookmarkFilter
import ceui.pixiv.db.mirror.BookmarkMirrorMapper
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.BookmarkSort
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.hjq.toast.Toaster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FollowingExportSheetTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val shelf = BookmarkShelf(123L, MirrorContentType.USER, MirrorRestrict.PUBLIC)
    private lateinit var db: AppDatabase
    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var host: FragmentActivity
    private lateinit var owner: Fragment

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Toaster.init(app)
        Shaft.sSettings = Settings()
        Shaft.sGson = Gson()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        host = controller.get()
        host.setTheme(R.style.AppTheme)
        owner = Fragment(android.R.layout.simple_list_item_1)
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, owner).commitNow()
    }

    @After fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller.pause().stop().destroy()
        models.clear()
        AppDatabase.destroyInstance()
        db.close()
        Dispatchers.resetMain()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `dragging first field directly to last keeps displayed and copied order aligned`() = runTest(dispatcher) {
        insert(shelf, 1..1)
        show()
        move(0, 2)

        assertEquals("user 1 · @account1 · 1", copy())
    }

    @Test fun `dragging last field directly to first preserves the intervening field order`() = runTest(dispatcher) {
        insert(shelf, 1..1)
        show()
        move(2, 0)

        assertEquals("@account1 · 1 · user 1", copy())
    }

    @Test fun `export reads every matching private page in sort order and skips bad payloads`() = runTest(dispatcher) {
        insert(shelf, 1..10)
        val privateShelf = shelf.copy(restrict = MirrorRestrict.PRIVATE)
        insert(privateShelf, 1..302, "keep")
        insert(privateShelf, 303..303, "exclude")
        val dao = db.bookmarkMirrorDao()
        dao.insertRows(listOf(
            requireNotNull(dao.findRow(privateShelf.key, 2)).copy(payloadJson = "broken"),
            requireNotNull(dao.findRow(privateShelf.key, 3)).copy(payloadJson = "{}"),
        ))
        show(privateShelf, BookmarkFilter(
            shelfKey = privateShelf.key, keyword = "keep", sort = BookmarkSort.BOOKMARK_OLDEST,
        ))

        val expected = (302 downTo 1).filter { it != 2 && it != 3 }
            .joinToString("\n") { "$it · keep $it · @account$it" }
        assertEquals(expected, copy())
    }

    @Test fun `snapshot keeps page boundaries stable while mirror pruning is queued`() = runTest(dispatcher) {
        insert(shelf, 1..301)
        val filter = BookmarkFilter(shelf.key)
        val firstPageRead = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            firstPageRead.await()
            writerStarted.complete(Unit)
            db.withTransaction {
                db.bookmarkMirrorDao().deleteTarget(shelf.ownerUid, shelf.contentType.code, 1)
            }
        }

        val ids = BookmarkLibraryRepo.withReadSnapshot(filter) { readPage ->
            assertTrue(db.inTransaction())
            val first = readPage(300, 0)
            firstPageRead.complete(Unit)
            writerStarted.await()
            (first + readPage(300, 300)).map { it.targetId }
        }
        writer.await()

        assertEquals((1L..301L).toList(), ids)
        assertEquals((2L..301L).toList(), BookmarkLibraryRepo.page(filter, 400, 0).map { it.targetId })
    }

    @Test fun `cancelling snapshot releases the transaction for subsequent mirror writes`() = runTest(dispatcher) {
        insert(shelf, 1..1)
        val reading = CompletableDeferred<Unit>()
        val export = async {
            BookmarkLibraryRepo.withReadSnapshot(BookmarkFilter(shelf.key)) { readPage ->
                readPage(300, 0)
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        reading.await()
        export.cancelAndJoin()
        db.withTransaction {
            db.bookmarkMirrorDao().deleteTarget(shelf.ownerUid, shelf.contentType.code, 1)
        }

        assertTrue(BookmarkLibraryRepo.page(BookmarkFilter(shelf.key), 300, 0).isEmpty())
    }

    @Test fun `dismissing while export waits for a transaction prevents a late clipboard write`() = runTest(dispatcher) {
        insert(shelf, 1..1)
        ClipBoardUtils.putTextIntoClipboard(host, "keep clipboard", false)
        show()
        val writing = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            db.withTransaction {
                writing.complete(Unit)
                releaseWriter.await()
            }
        }
        writing.await()
        val sheet = requireNotNull(ShadowDialog.getLatestDialog()) as WitBottomSheet
        copyButton().performClick()
        val exports = (owner.viewLifecycleOwner.lifecycleScope.coroutineContext.job.children +
            sheet.lifecycleScope.coroutineContext.job.children).toList()
        runCurrent()

        // Dialog 的 OnDismissListener 要到下一条主线程消息才回调；窗口此时已经关闭。
        sheet.dismiss()
        releaseWriter.complete(Unit)
        writer.await()
        exports.forEach { it.join() }

        assertEquals("keep clipboard", ClipBoardUtils.getClipboardContent(host))
    }

    private fun insert(targetShelf: BookmarkShelf, ids: IntRange, prefix: String = "user") {
        db.bookmarkMirrorDao().insertRows(ids.map { id ->
            BookmarkMirrorMapper.fromUserPreview(
                targetShelf,
                UserPreview(user = User(id = id.toLong(), name = "$prefix $id", account = "account$id")),
                bookmarkSeq = -id.toLong(), generation = 1, now = 0L,
            ).row
        })
    }

    private fun show(targetShelf: BookmarkShelf = shelf, filter: BookmarkFilter = BookmarkFilter(targetShelf.key)) {
        val model = BookmarkLibraryViewModel()
        models.put("library", model)
        model.bind(targetShelf)
        ReflectionHelpers.getField<MutableStateFlow<BookmarkFilter>>(model, "_filter").value = filter
        FollowingExportSheet.show(owner, model)
    }

    private fun move(from: Int, to: Int) {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val list = (dialog.window!!.decorView as ViewGroup).descendants.filterIsInstance<RecyclerView>().single()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 800, 600)
        val helper = (0 until list.itemDecorationCount).map(list::getItemDecorationAt)
            .filterIsInstance<ItemTouchHelper>().single()
        val callback = ReflectionHelpers.getField<ItemTouchHelper.Callback>(helper, "mCallback")
        assertTrue(callback.onMove(
            list, requireNotNull(list.findViewHolderForAdapterPosition(from)),
            requireNotNull(list.findViewHolderForAdapterPosition(to)),
        ))
    }

    private suspend fun copy(): String? {
        val sheet = requireNotNull(ShadowDialog.getLatestDialog()) as WitBottomSheet
        copyButton().performClick()
        sheet.lifecycleScope.coroutineContext.job.children.toList().forEach { it.join() }
        return ClipBoardUtils.getClipboardContent(host)
    }

    private fun copyButton(): TextView {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        return (dialog.window!!.decorView as ViewGroup).descendants.filterIsInstance<TextView>()
            .single { it.text == host.getString(R.string.following_export_copy) }
    }
}
