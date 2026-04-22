package ceui.pixiv.ui.novel.reader.ui

import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import ceui.lisa.databinding.LayoutReaderSearchOverlayBinding

class ReaderSearchOverlay(private val binding: LayoutReaderSearchOverlayBinding) {

    val view: View get() = binding.root

    var onQueryChanged: ((String) -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onRegexToggle: ((Boolean) -> Unit)? = null
    var onListClick: (() -> Unit)? = null

    private var regexMode = false

    init {
        binding.root.visibility = View.GONE

        binding.editSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onQueryChanged?.invoke(s?.toString().orEmpty())
            }
        })
        binding.editSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onNext?.invoke()
                true
            } else false
        }
        binding.editSearchQuery.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                onNext?.invoke()
                true
            } else false
        }

        binding.btnCloseSearch.setOnClickListener { onClose?.invoke() }
        binding.btnSearchNext.setOnClickListener { onNext?.invoke() }
        binding.btnSearchPrev.setOnClickListener { onPrev?.invoke() }
        binding.btnSearchList.setOnClickListener { onListClick?.invoke() }
        binding.btnSearchRegex.setOnClickListener {
            regexMode = !regexMode
            applyRegexStyle()
            onRegexToggle?.invoke(regexMode)
        }
        applyRegexStyle()
    }

    /** 搜索 overlay 是否处于可见态（用于返回手势优先关闭它）。 */
    fun isShown(): Boolean = binding.root.visibility == View.VISIBLE

    fun setShown(shown: Boolean) {
        if (shown) {
            binding.root.visibility = View.VISIBLE
            binding.root.alpha = 0f
            binding.root.translationY = -binding.root.height.toFloat().coerceAtLeast(0f)
            binding.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
            binding.editSearchQuery.requestFocus()
            showKeyboard()
        } else {
            hideKeyboard()
            binding.root.animate()
                .alpha(0f)
                .translationY(-binding.root.height.toFloat())
                .setDuration(160)
                .withEndAction { binding.root.visibility = View.GONE }
                .start()
        }
    }

    fun clear() {
        binding.editSearchQuery.text = null
        setCount(0, 0)
    }

    fun currentQuery(): String = binding.editSearchQuery.text?.toString().orEmpty()

    fun setCount(currentIndex: Int, total: Int) {
        val ctx = binding.root.context
        binding.txtSearchCount.text = if (total == 0) {
            ctx.getString(ceui.lisa.R.string.reader_search_no_result)
        } else {
            ctx.getString(ceui.lisa.R.string.reader_progress_format, currentIndex + 1, total)
        }
        binding.btnSearchList.visibility = if (total > 0) View.VISIBLE else View.GONE
    }

    private fun applyRegexStyle() {
        binding.btnSearchRegex.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = binding.root.context.resources.displayMetrics.density * 4
            setColor(if (regexMode) 0xFF5B6EFF.toInt() else 0x33FFFFFF)
        }
    }

    private fun showKeyboard() {
        val imm = binding.root.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.editSearchQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = binding.root.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearchQuery.windowToken, 0)
    }
}
