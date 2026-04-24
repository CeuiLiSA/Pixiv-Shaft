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

interface NoteEditorCallback {
    fun onNoteSaved(annotationId: Long, charStart: Int, charEnd: Int, excerpt: String, noteText: String, color: Int)
    fun onNoteDeleted(annotationId: Long)
}

class NoteEditorDialog : DialogFragment() {

    private val existingNote: String by lazy { requireArguments().getString(ARG_EXISTING_NOTE, "") }
    private val excerpt: String by lazy { requireArguments().getString(ARG_EXCERPT, "") }
    private val annotationId: Long by lazy { requireArguments().getLong(ARG_ANNOTATION_ID, 0L) }
    private val charStart: Int by lazy { requireArguments().getInt(ARG_CHAR_START, 0) }
    private val charEnd: Int by lazy { requireArguments().getInt(ARG_CHAR_END, 0) }
    private val highlightColor: Int by lazy { requireArguments().getInt(ARG_COLOR, 0) }
    private val showDelete: Boolean by lazy { requireArguments().getBoolean(ARG_SHOW_DELETE, false) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), 0)
        }

        if (excerpt.isNotEmpty()) {
            val excerptView = TextView(ctx).apply {
                text = "\u300C${excerpt.take(200)}\u300D"
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

        val callback = parentFragment as? NoteEditorCallback

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (existingNote.isEmpty()) getString(ceui.lisa.R.string.note_add_title) else getString(ceui.lisa.R.string.note_edit_title))
            .setView(container)
            .setPositiveButton(ceui.lisa.R.string.action_save) { _, _ ->
                callback?.onNoteSaved(annotationId, charStart, charEnd, excerpt, edit.text.toString().trim(), highlightColor)
            }
            .setNegativeButton(ceui.lisa.R.string.action_cancel, null)

        if (showDelete) {
            builder.setNeutralButton(ceui.lisa.R.string.action_delete) { _, _ ->
                callback?.onNoteDeleted(annotationId)
            }
        }
        return builder.create()
    }

    companion object {
        const val TAG = "NoteEditorDialog"
        private const val ARG_EXISTING_NOTE = "existing_note"
        private const val ARG_EXCERPT = "excerpt"
        private const val ARG_ANNOTATION_ID = "annotation_id"
        private const val ARG_CHAR_START = "char_start"
        private const val ARG_CHAR_END = "char_end"
        private const val ARG_COLOR = "color"
        private const val ARG_SHOW_DELETE = "show_delete"

        fun newInstance(
            annotationId: Long = 0L,
            charStart: Int = 0,
            charEnd: Int = 0,
            excerpt: String = "",
            existingNote: String = "",
            color: Int = 0,
            showDelete: Boolean = false,
        ) = NoteEditorDialog().apply {
            arguments = Bundle().apply {
                putLong(ARG_ANNOTATION_ID, annotationId)
                putInt(ARG_CHAR_START, charStart)
                putInt(ARG_CHAR_END, charEnd)
                putString(ARG_EXCERPT, excerpt)
                putString(ARG_EXISTING_NOTE, existingNote)
                putInt(ARG_COLOR, color)
                putBoolean(ARG_SHOW_DELETE, showDelete)
            }
        }
    }
}
