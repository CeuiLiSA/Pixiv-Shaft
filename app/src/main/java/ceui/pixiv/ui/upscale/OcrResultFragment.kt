package ceui.pixiv.ui.upscale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import ceui.pixiv.ui.translate.OfflineTranslator
import ceui.pixiv.ui.translate.TranslationModel
import ceui.pixiv.ui.translate.TranslationModelManager
import kotlinx.coroutines.launch
import timber.log.Timber

class OcrResultFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ocr_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        val content = view.findViewById<LinearLayout>(R.id.ocr_content)
        val texts = arguments?.getStringArrayList(KEY_TEXTS) ?: return

        val model = TranslationModel.OPUS_MT_JA_ZH

        // If model not downloaded, prompt user to download
        if (!TranslationModelManager.isModelReady(requireContext(), model)) {
            Common.showToast(R.string.string_translation_model_needed)
            val intent = Intent(requireContext(), TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "翻译模型下载")
            intent.putExtra("translation_model_name", model.name)
            startActivity(intent)
        }

        data class CardHolder(
            val jaText: String,
            val translatedView: TextView,
            val loadingView: View
        )
        val holders = mutableListOf<CardHolder>()

        for (jaText in texts) {
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_ocr_text, content, false)
            card.findViewById<TextView>(R.id.ocr_text).text = jaText
            val translatedView = card.findViewById<TextView>(R.id.ocr_translated)
            val loadingView = card.findViewById<View>(R.id.translate_loading)

            card.setOnClickListener {
                val zhText = translatedView.text?.toString() ?: ""
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OCR", if (zhText.isNotEmpty()) "$jaText\n$zhText" else jaText))
                Common.showToast("已复制")
            }

            content.addView(card)
            holders.add(CardHolder(jaText, translatedView, loadingView))
        }

        // Only translate if model is available
        if (TranslationModelManager.isModelReady(requireContext(), model)) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (!OfflineTranslator.isLoaded) {
                    try {
                        OfflineTranslator.loadModel(requireContext(), model)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to load offline translator")
                        Common.showToast(R.string.string_translate_failed)
                        for (holder in holders) {
                            holder.loadingView.visibility = View.GONE
                        }
                        return@launch
                    }
                }

                for (holder in holders) {
                    try {
                        val zhText = OfflineTranslator.translate(holder.jaText)
                        holder.loadingView.visibility = View.GONE
                        holder.translatedView.text = zhText
                        holder.translatedView.visibility = View.VISIBLE
                    } catch (e: Exception) {
                        Timber.e(e, "Offline translate failed for: ${holder.jaText}")
                        holder.loadingView.visibility = View.GONE
                    }
                }
            }
        } else {
            // Model not ready, hide all loading indicators
            for (holder in holders) {
                holder.loadingView.visibility = View.GONE
            }
        }
    }

    companion object {
        const val KEY_TEXTS = "ocr_texts"

        @JvmStatic
        fun newInstance(texts: ArrayList<String>): OcrResultFragment {
            return OcrResultFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(KEY_TEXTS, texts)
                }
            }
        }
    }
}
