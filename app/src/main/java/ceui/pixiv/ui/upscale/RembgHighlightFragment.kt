package ceui.pixiv.ui.upscale

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.common.saveImageToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * iPhone / 国产手机相册风格的抠图确认页面。
 *
 * 流程：rembg 处理完成后进入此页 → 主体高亮（背景暗化 + 呼吸动画）→ 用户确认保存或预览。
 */
class RembgHighlightFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_rembg_highlight, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Immersive fullscreen
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        // Apply safe area insets to root container
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        val originalPath = arguments?.getString(KEY_ORIGINAL) ?: return
        val rembgPath = arguments?.getString(KEY_REMBG) ?: return

        val highlightView = view.findViewById<SubjectHighlightView>(R.id.subject_highlight)
        val hintText = view.findViewById<TextView>(R.id.hint_text)

        // Load bitmaps off main thread
        viewLifecycleOwner.lifecycleScope.launch {
            val (original, subject) = withContext(Dispatchers.IO) {
                val orig = BitmapFactory.decodeFile(originalPath)
                val subj = BitmapFactory.decodeFile(rembgPath)
                orig to subj
            }
            if (original != null && subject != null) {
                highlightView.setImages(original, subject)
                // Fade in hint text after a short delay
                hintText.animate()
                    .alpha(1f)
                    .setStartDelay(400)
                    .setDuration(500)
                    .start()
            }
        }

        // Save cutout to gallery
        view.findViewById<TextView>(R.id.btn_save).setOnClickListener {
            val file = File(rembgPath)
            if (file.exists()) {
                saveImageToGallery(requireContext(), file, "rembg_${System.currentTimeMillis()}.png")
            }
        }

        // Preview cutout on checkerboard background (zoomable)
        view.findViewById<TextView>(R.id.btn_preview).setOnClickListener {
            val intent = Intent(requireContext(), TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "抠图预览")
            intent.putExtra("rembg_path", rembgPath)
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        view?.findViewById<SubjectHighlightView>(R.id.subject_highlight)?.cleanup()
        super.onDestroyView()
    }

    companion object {
        const val KEY_ORIGINAL = "original_path"
        const val KEY_REMBG = "rembg_path"

        @JvmStatic
        fun newInstance(originalPath: String, rembgPath: String): RembgHighlightFragment {
            return RembgHighlightFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_ORIGINAL, originalPath)
                    putString(KEY_REMBG, rembgPath)
                }
            }
        }
    }
}
