package ceui.pixiv.ui.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import kotlinx.coroutines.launch

class SakuraTranslateDemoFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_sakura_translate_demo, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val inputText = view.findViewById<EditText>(R.id.input_text)
        val glossaryText = view.findViewById<EditText>(R.id.glossary_text)
        val translateButton = view.findViewById<Button>(R.id.translate_button)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
        val progressText = view.findViewById<TextView>(R.id.progress_text)
        val outputText = view.findViewById<TextView>(R.id.output_text)
        val metaText = view.findViewById<TextView>(R.id.meta_text)
        val copyButton = view.findViewById<TextView>(R.id.copy_button)

        copyButton.setOnClickListener {
            val text = outputText.text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                val cm = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sakura_translation", text))
                Common.showToast(getString(R.string.sakura_demo_copied))
            }
        }

        translateButton.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val raw = inputText.text?.toString().orEmpty()
            val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                Common.showToast(getString(R.string.sakura_demo_empty_input))
                return@setOnClickListener
            }

            val model = SakuraModel.SAKURA_1_5B
            if (!SakuraModelManager.isModelReady(ctx, model)) {
                Common.showToast(getString(R.string.string_sakura_model_needed))
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Sakura翻译模型下载")
                intent.putExtra("sakura_model_name", model.name)
                startActivity(intent)
                return@setOnClickListener
            }

            val glossary = glossaryText.text?.toString()?.trim().orEmpty()

            translateButton.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressText.text = getString(R.string.sakura_demo_progress_loading)
            outputText.text = ""
            metaText.text = ""

            val startMs = System.currentTimeMillis()

            viewLifecycleOwner.lifecycleScope.launch {
                val results = SakuraTranslator.translateBatch(
                    context = ctx,
                    texts = lines,
                    glossary = glossary.ifEmpty { null },
                    onProgress = { done, total ->
                        view.post {
                            progressText.text = getString(
                                R.string.sakura_demo_progress_fmt, done, total
                            )
                        }
                    }
                )

                val elapsedMs = System.currentTimeMillis() - startMs
                val rendered = results.joinToString("\n") { it ?: "⟨翻译失败⟩" }
                outputText.text = rendered
                progressBar.visibility = View.GONE
                progressText.text = ""
                translateButton.isEnabled = true
                val failed = results.count { it == null }
                metaText.text = getString(
                    R.string.sakura_demo_meta_fmt,
                    lines.size,
                    failed,
                    elapsedMs / 1000.0
                )
            }
        }
    }
}
