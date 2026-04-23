package ceui.pixiv.ui.novel.reader.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment

/**
 * Simple multi-line note editor. Configured with an optional existing note and
 * returns the new text via callback. Uses AppCompat AlertDialog so it styles
 * consistently with the rest of the app.
 */
class NoteEditorDialog : DialogFragment() {

    private var existingNote: String = ""
    private var excerpt: String = ""
    private var onSave: ((String) -> Unit)? = null
    private var onDelete: (() -> Unit)? = null

    fun configure(
        existingNote: String = "",
        excerpt: String = "",
        onDelete: (() -> Unit)? = null,
        onSave: (String) -> Unit,
    ) {
        this.existingNote = existingNote
        this.excerpt = excerpt
        this.onDelete = onDelete
        this.onSave = onSave
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), 0)
        }

        if (excerpt.isNotEmpty()) {
            val excerptView = TextView(ctx).apply {
                text = "「${excerpt.take(200)}」"
                setTextColor(ContextCompat.getColor(ctx, ceui.lisa.R.color.v3_text_3))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.START
            }
            container.addView(excerptView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            })
        }

        val edit = EditText(ctx).apply {
            setText(existingNote)
            setHint(ceui.lisa.R.string.note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.TOP or Gravity.START
        }
        container.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (existingNote.isEmpty()) getString(ceui.lisa.R.string.note_add_title) else getString(ceui.lisa.R.string.note_edit_title))
            .setView(container)
            .setPositiveButton(ceui.lisa.R.string.action_save) { _, _ ->
                onSave?.invoke(edit.text.toString().trim())
            }
            .setNegativeButton(ceui.lisa.R.string.action_cancel, null)

        if (onDelete != null && existingNote.isNotEmpty()) {
            builder.setNeutralButton(ceui.lisa.R.string.action_delete) { _, _ -> onDelete?.invoke() }
        }
        return builder.create()
    }

    companion object {
        const val TAG = "NoteEditorDialog"
    }
}
