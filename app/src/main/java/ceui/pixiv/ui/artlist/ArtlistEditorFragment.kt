package ceui.pixiv.ui.artlist

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.witstudio.theme.*
import kotlinx.coroutines.launch

class ArtlistEditorFragment : Fragment(R.layout.fragment_artlist_editor) {
    private val model: ArtlistEditorModel by viewModels()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val fields = view.findViewById<LinearLayout>(R.id.artlist_editor_fields)
        val scroll = view.findViewById<View>(R.id.artlist_editor_scroll)
        setupV3Toolbar(view, getString(if (model.listId > 0) R.string.artlist_edit else R.string.artlist_create), scroll)
        scroll.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left, context.dp(720))
            if (fields.layoutParams.width != width) fields.layoutParams = FrameLayout.LayoutParams(width, -2, android.view.Gravity.CENTER_HORIZONTAL)
        }
        fun add(view: View, gap: Int = 16) = fields.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(gap) })
        add(context.label(getString(R.string.artlist_public_hint), 14f, 400, context.color(R.color.v3_text_2)).apply { lineHeightRatio(1.5f) }, 0)
        add(context.label(getString(R.string.artlist_name), 18f, 600, context.color(R.color.v3_text_1)), 28)
        val name = EditText(context).apply {
            hint = getString(R.string.artlist_name_hint)
            setText(model.name)
            textSize = 17f; typeface = context.v3Font(500)
            setTextColor(context.color(R.color.v3_text_1)); setHintTextColor(context.color(R.color.v3_text_2))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(120))
            minHeight = context.dp(56)
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            background = context.card()
            doAfterTextChanged { model.name = it?.toString().orEmpty() }
        }
        add(name, 10)
        add(context.label(getString(R.string.artlist_description), 18f, 600, context.color(R.color.v3_text_1)), 28)
        val description = EditText(context).apply {
            hint = getString(R.string.artlist_description_hint)
            setText(model.description)
            textSize = 15f; typeface = context.v3Font(400)
            setTextColor(context.color(R.color.v3_text_1)); setHintTextColor(context.color(R.color.v3_text_2))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(2000))
            minLines = 5; gravity = android.view.Gravity.TOP
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            background = context.card()
            doAfterTextChanged { model.description = it?.toString().orEmpty() }
        }
        add(description, 10)
        val error = context.label("", 14f, 400, context.color(R.color.v3_danger)).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            lineHeightRatio(1.5f)
        }
        add(error)
        val save = context.artlistButton(getString(R.string.artlist_save_changes), true) { model.save() }
        add(save)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (name.text.toString() != model.name) name.setText(model.name)
                    if (description.text.toString() != model.description) description.setText(model.description)
                    name.isEnabled = !state.loading && !state.saving
                    description.isEnabled = name.isEnabled
                    save.isEnabled = !state.loading && !state.saving
                    save.setText(if (state.saving) R.string.artlist_saving else R.string.artlist_save_changes)
                    error.text = state.error; error.isVisible = state.error != null
                    state.completed?.let { item ->
                        model.consumeResult()
                        Common.showToast(R.string.artlist_operation_done)
                        if (model.listId == 0L) ArtlistNavigation.open(context, item.id)
                        requireActivity().finish()
                    }
                }
            }
        }
    }
}
