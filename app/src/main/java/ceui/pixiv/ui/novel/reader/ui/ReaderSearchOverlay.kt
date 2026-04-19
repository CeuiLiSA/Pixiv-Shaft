package ceui.pixiv.ui.novel.reader.ui

import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import ceui.lisa.R

/**
 * Wrapper around the search overlay include. Calls back to the host for each
 * keystroke (debounced elsewhere if needed) and for next / previous / regex
 * toggles. Visibility + animation are driven by [setShown].
 */
class ReaderSearchOverlay(private val rootView: View) {

    private val btnClose = rootView.findViewById<ImageButton>(R.id.btn_close_search)
    private val editQuery = rootView.findViewById<EditText>(R.id.edit_search_query)
    private val txtCount = rootView.findViewById<TextView>(R.id.txt_search_count)
    private val btnPrev = rootView.findViewById<TextView>(R.id.btn_search_prev)
    private val btnNext = rootView.findViewById<TextView>(R.id.btn_search_next)
    private val btnRegex = rootView.findViewById<TextView>(R.id.btn_search_regex)

    val view: View get() = rootView

    var onQueryChanged: ((String) -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onRegexToggle: ((Boolean) -> Unit)? = null

    private var regexMode = false

    init {
        rootView.visibility = View.GONE

        editQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onQueryChanged?.invoke(s?.toString().orEmpty())
            }
        })
        editQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onNext?.invoke()
                true
            } else false
        }
        editQuery.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                onNext?.invoke()
                true
            } else false
        }

        btnClose.setOnClickListener { onClose?.invoke() }
        btnNext.setOnClickListener { onNext?.invoke() }
        btnPrev.setOnClickListener { onPrev?.invoke() }
        btnRegex.setOnClickListener {
            regexMode = !regexMode
            applyRegexStyle()
            onRegexToggle?.invoke(regexMode)
        }
        applyRegexStyle()
    }

    fun setShown(shown: Boolean) {
        if (shown) {
            rootView.visibility = View.VISIBLE
            rootView.alpha = 0f
            rootView.translationY = -rootView.height.toFloat().coerceAtLeast(0f)
            rootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
            editQuery.requestFocus()
            showKeyboard()
        } else {
            hideKeyboard()
            rootView.animate()
                .alpha(0f)
                .translationY(-rootView.height.toFloat())
                .setDuration(160)
                .withEndAction { rootView.visibility = View.GONE }
                .start()
        }
    }

    fun clear() {
        editQuery.text = null
        setCount(0, 0)
    }

    fun currentQuery(): String = editQuery.text?.toString().orEmpty()

    fun isRegex(): Boolean = regexMode

    fun setCount(currentIndex: Int, total: Int) {
        txtCount.text = if (total == 0) "无结果" else "${currentIndex + 1} / $total"
    }

    private fun applyRegexStyle() {
        btnRegex.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = btnRegex.context.resources.displayMetrics.density * 4
            setColor(if (regexMode) 0xFF5B6EFF.toInt() else 0x33FFFFFF)
        }
    }

    private fun showKeyboard() {
        val imm = rootView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = rootView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editQuery.windowToken, 0)
    }
}
