package ceui.lisa.activities

import android.app.Dialog
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.descendants
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.SearchEntity
import ceui.lisa.databinding.FragmentNewSearchBinding
import ceui.lisa.fragments.FragmentSearch
import ceui.lisa.utils.Settings
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.widgets.V3TagFlowView
import ceui.pixiv.witstudio.dialog.WitDialog
import com.blankj.utilcode.util.Utils
import com.hjq.toast.Toaster
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class SearchTagDeletionTest {
    private lateinit var host: FragmentActivity
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Toaster.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        host.setTheme(R.style.AppTheme)
    }

    @After fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        AppDatabase.destroyInstance()
        db.close()
    }

    @Test fun `history delete waits for confirmation and keeps the original target after reorder`() {
        val fragment = FragmentSearch()
        host.supportFragmentManager.beginTransaction().add(fragment, "search")
            .setMaxLifecycle(fragment, Lifecycle.State.CREATED).commitNow()
        val records = mutableListOf(history(1, "first tag"), history(2, "second tag"))
        records.forEach { db.searchDao().insert(it) }
        val flow = V3TagFlowView(host)
        ReflectionHelpers.callInstanceMethod<Void>(fragment, "bindHistoryFlow",
            ReflectionHelpers.ClassParameter.from(V3TagFlowView::class.java, flow),
            ReflectionHelpers.ClassParameter.from(List::class.java, records))
        val delete = flow.descendants.filterIsInstance<ImageButton>().first()

        delete.performClick()
        val cancelled = latestConfirmation()
        assertEquals(2, db.searchDao().getRecentUnpinned(50).size)
        action(cancelled, R.string.string_142).performClick()
        assertEquals(2, db.searchDao().getRecentUnpinned(50).size)

        delete.performClick()
        val confirmed = latestConfirmation()
        records.reverse()
        action(confirmed, R.string.action_delete).performClick()
        assertFalse(confirmed.isShowing)
        assertEquals(listOf(2), db.searchDao().getRecentUnpinned(50).map { it.id })
    }

    @Test fun `search tag cancel preserves draft and confirmation alone removes and searches`() {
        val binding = FragmentNewSearchBinding.inflate(LayoutInflater.from(host))
        binding.searchTagsFlow.showRemoveIcon = true
        val search = Robolectric.buildActivity(SearchActivity::class.java).get()
        val model = SearchModel()
        ReflectionHelpers.setField(search, "baseBind", binding)
        ReflectionHelpers.setField(search, "mContext", host)
        ReflectionHelpers.setField(search, "searchModel", model)
        val chips = ReflectionHelpers.getField<MutableList<String>>(search, "committedTags")
        chips.addAll(listOf("keep", "remove"))
        binding.searchTagsFlow.editor!!.setText("draft")
        model.keyword.value = "keep remove draft"
        fun request() = ReflectionHelpers.callInstanceMethod<Void>(search, "confirmRemoveTag",
            ReflectionHelpers.ClassParameter.from(String::class.java, "remove"))

        request()
        latestConfirmation().cancel()
        assertEquals(listOf("keep", "remove"), chips)
        assertEquals("keep remove draft", model.keyword.value)
        assertNull(model.nowGo.value)

        // 长按菜单的删除也走同一个确认入口，不能越过确认直接修改查询。
        ReflectionHelpers.callInstanceMethod<Void>(search, "showTagActionMenu",
            ReflectionHelpers.ClassParameter.from(String::class.java, "remove"))
        val menu = ShadowDialog.getLatestDialog()
        val menuText = (menu.window!!.decorView as ViewGroup).descendants.filterIsInstance<TextView>()
            .first { it.text == host.getString(R.string.tag_action_delete) }
        (if (menuText.isClickable) menuText else menuText.parent as android.view.View).performClick()
        assertFalse(menu.isShowing)
        val confirmation = latestConfirmation()
        assertEquals(listOf("keep", "remove"), chips)
        assertNull(model.nowGo.value)
        action(confirmation, R.string.action_delete).performClick()
        assertEquals(listOf("keep"), chips)
        assertEquals("draft", binding.searchTagsFlow.editor!!.text.toString())
        assertEquals("keep draft", model.keyword.value)
        assertEquals("search_now", model.nowGo.value)
    }

    private fun latestConfirmation(): Dialog = ShadowDialog.getLatestDialog().also {
        assertTrue(it is WitDialog)
        assertTrue(it.isShowing)
        assertEquals(it.context.getColor(ceui.pixiv.witstudio.R.color.wit_danger),
            action(it, R.string.action_delete).currentTextColor)
    }

    private fun action(dialog: Dialog, label: Int): TextView =
        (dialog.window!!.decorView as ViewGroup).descendants.filterIsInstance<TextView>()
            .first { it.isClickable && it.text == host.getString(label) }

    private fun history(id: Int, name: String) = SearchEntity().apply {
        this.id = id
        keyword = name
        searchType = 0
    }
}
