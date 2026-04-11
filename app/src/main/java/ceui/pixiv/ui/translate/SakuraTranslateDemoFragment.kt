package ceui.pixiv.ui.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentSakuraTranslateDemoBinding
import ceui.lisa.fragments.SwipeFragment
import ceui.lisa.utils.Common
import com.scwang.smart.refresh.layout.SmartRefreshLayout

class SakuraTranslateDemoFragment : SwipeFragment<FragmentSakuraTranslateDemoBinding>() {

    private val viewModel by viewModels<SakuraTranslateDemoViewModel>()

    override fun initLayout() {
        mLayoutID = R.layout.fragment_sakura_translate_demo
    }

    override fun getSmartRefreshLayout(): SmartRefreshLayout = baseBind.refreshLayout

    override fun enableRefresh(): Boolean = false

    override fun enableLoadMore(): Boolean = false

    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }

        refreshCounter()
        baseBind.inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshCounter()
        })

        baseBind.copyButton.setOnClickListener {
            val text = baseBind.outputText.text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                val cm = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sakura_translation", text))
                Common.showToast(getString(R.string.sakura_demo_copied))
            }
        }

        baseBind.translateButton.setOnClickListener { onTranslateClicked() }

        observeViewModel()
    }

    private fun refreshCounter() {
        val lines = baseBind.inputText.text?.toString()
            ?.split('\n')
            ?.count { it.isNotBlank() }
            ?: 0
        baseBind.inputCounter.text = getString(R.string.sakura_demo_line_count_fmt, lines)
    }

    private fun observeViewModel() {
        viewModel.isTranslating.observe(viewLifecycleOwner) { translating ->
            val running = translating == true
            baseBind.translateButton.isEnabled = !running
            baseBind.translateButton.alpha = if (running) 0.5f else 1f
            baseBind.progressRow.isVisible = running
            if (running && viewModel.progress.value == null) {
                baseBind.progressText.text = getString(R.string.sakura_demo_progress_loading)
            }
        }

        viewModel.progress.observe(viewLifecycleOwner) { pair ->
            if (pair != null) {
                baseBind.progressText.text = getString(
                    R.string.sakura_demo_progress_fmt, pair.first, pair.second
                )
            }
        }

        viewModel.output.observe(viewLifecycleOwner) { text ->
            baseBind.outputText.text = text.orEmpty()
            baseBind.outputCard.isVisible = !text.isNullOrEmpty()
        }

        viewModel.meta.observe(viewLifecycleOwner) { meta ->
            baseBind.metaText.text = meta?.let {
                getString(
                    R.string.sakura_demo_meta_fmt,
                    it.total,
                    it.failed,
                    it.elapsedMs / 1000.0
                )
            }.orEmpty()
        }
    }

    private fun onTranslateClicked() {
        val ctx = context ?: return
        val raw = baseBind.inputText.text?.toString().orEmpty()
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            Common.showToast(getString(R.string.sakura_demo_empty_input))
            return
        }
        val glossary = baseBind.glossaryText.text?.toString()?.trim().orEmpty()
        val started = viewModel.translate(ctx, lines, glossary.ifEmpty { null })
        if (!started) {
            Common.showToast(getString(R.string.string_sakura_model_needed))
            val model = SakuraModel.SAKURA_1_5B
            val intent = Intent(ctx, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Sakura翻译模型下载")
            intent.putExtra("sakura_model_name", model.name)
            startActivity(intent)
        }
    }
}
