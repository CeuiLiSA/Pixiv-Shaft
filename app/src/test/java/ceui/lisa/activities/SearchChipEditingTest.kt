package ceui.lisa.activities

import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import ceui.lisa.databinding.FragmentNewSearchBinding
import ceui.lisa.viewmodel.SearchModel
import com.blankj.utilcode.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class SearchChipEditingTest {
    private lateinit var search: SearchActivity
    private lateinit var binding: FragmentNewSearchBinding
    private lateinit var model: SearchModel
    private lateinit var chips: MutableList<String>

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        binding = FragmentNewSearchBinding.inflate(LayoutInflater.from(context))
        binding.searchTagsFlow.showRemoveIcon = true
        search = Robolectric.buildActivity(SearchActivity::class.java).get()
        model = SearchModel()
        ReflectionHelpers.setField(search, "baseBind", binding)
        ReflectionHelpers.setField(search, "mContext", context)
        ReflectionHelpers.setField(search, "searchModel", model)
        chips = ReflectionHelpers.getField(search, "committedTags")
    }

    @Test
    fun `editing preserves unfinished input without starting a search`() {
        chips.addAll(listOf("X", "B"))
        binding.searchTagsFlow.editor!!.setText("A")
        edit("B")
        assertEquals(listOf("X", "A"), chips)
        assertEquals("B", binding.searchTagsFlow.editor!!.text.toString())
        assertEquals("X A B", model.keyword.value)
        assertNull(model.nowGo.value)
    }

    @Test
    fun `empty or duplicate pending input does not introduce empty or duplicate chips`() {
        for (pending in listOf("", "   ", "X", "B")) {
            chips.clear()
            chips.addAll(listOf("X", "B"))
            binding.searchTagsFlow.editor!!.setText(pending)
            edit("B")
            assertEquals("pending=$pending", listOf("X"), chips)
            assertEquals("X B", model.keyword.value)
            assertNull(model.nowGo.value)
        }
    }

    @Test
    fun `consecutive edits keep the previous draft including a multiword tag`() {
        chips.addAll(listOf("X", "B"))
        binding.searchTagsFlow.editor!!.setText("  初音 ミク  ")
        edit("B")
        edit("X")
        assertEquals(listOf("初音 ミク", "B"), chips)
        assertEquals("X", binding.searchTagsFlow.editor!!.text.toString())
        assertEquals("初音 ミク B X", model.keyword.value)
        assertEquals(1, binding.searchTagsFlow.editor!!.selectionStart)
        assertNull(model.nowGo.value)
    }

    private fun edit(name: String) {
        ReflectionHelpers.callInstanceMethod<Void>(search, "editTagFromChip",
            ReflectionHelpers.ClassParameter.from(String::class.java, name))
    }
}
