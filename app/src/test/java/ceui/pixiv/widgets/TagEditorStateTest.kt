package ceui.pixiv.widgets

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import com.blankj.utilcode.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class TagEditorStateTest {
    private lateinit var flow: V3TagFlowView
    private lateinit var editor: EditText
    private lateinit var outside: EditText

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(host, R.style.AppTheme)
        flow = V3TagFlowView(context).apply {
            showRemoveIcon = true
            setTagNames(listOf("X", "B"))
        }
        outside = EditText(context)
        host.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(outside)
            addView(flow)
        })
        shadowOf(Looper.getMainLooper()).idle()
        editor = flow.editor!!
    }

    @Test
    fun `rebuilding chips keeps editor attached with its focus and selection direction`() {
        editor.setText("draft words")
        editor.requestFocus()
        editor.setSelection(8, 2)
        var detaches = 0
        editor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { detaches++ }
        })
        flow.setTagNames(listOf("X", "B", "C"))
        flow.setTagNames(listOf("X"))
        assertEquals(0, detaches)
        assertTrue(editor.hasFocus())
        assertEquals("draft words", editor.text.toString())
        assertEquals(8, editor.selectionStart)
        assertEquals(2, editor.selectionEnd)
        assertEquals(flow.childCount - 1, flow.indexOfChild(editor))
    }

    @Test
    fun `removing a chip does not reopen a keyboard the user dismissed`() {
        editor.requestFocus()
        val imm = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editor, 0)
        imm.hideSoftInputFromWindow(editor.windowToken, 0)
        assertTrue(editor.hasFocus())
        assertFalse(shadowOf(imm).isSoftInputVisible)
        flow.setTagNames(listOf("X"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(editor.hasFocus())
        assertFalse(shadowOf(imm).isSoftInputVisible)
    }

    @Test
    fun `rebuilding chips does not take focus from another input`() {
        outside.requestFocus()
        flow.setTagNames(listOf("X"))
        assertTrue(outside.hasFocus())
        assertFalse(editor.hasFocus())
    }
}
