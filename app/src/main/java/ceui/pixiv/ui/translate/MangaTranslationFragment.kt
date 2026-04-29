package ceui.pixiv.ui.translate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.pixiv.ui.common.saveImageToGallery
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView
import kotlinx.coroutines.launch
import java.io.File

class MangaTranslationFragment : Fragment() {

    private var syncing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_manga_translation, container, false)
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

        val translatedPath = arguments?.getString(KEY_TRANSLATED) ?: return
        val originalPath = arguments?.getString(KEY_ORIGINAL) ?: return

        val imageOriginal = view.findViewById<SketchZoomImageView>(R.id.image_original)
        val imageTranslated = view.findViewById<SketchZoomImageView>(R.id.image_translated)

        imageOriginal.loadImage(File(originalPath))
        imageTranslated.loadImage(File(translatedPath))

        view.findViewById<TextView>(R.id.save_translated).setOnClickListener {
            val file = File(translatedPath)
            if (file.exists()) {
                saveImageToGallery(requireContext(), file, "manga_translated_${System.currentTimeMillis()}.png")
            }
        }

        setupZoomSync(imageOriginal, imageTranslated)
    }

    private fun setupZoomSync(original: SketchZoomImageView, translated: SketchZoomImageView) {
        viewLifecycleOwner.lifecycleScope.launch {
            original.zoomable.transformState.collect { transform ->
                if (syncing) return@collect
                syncing = true
                try {
                    translated.zoomable.scale(transform.scaleX, animated = false)
                    translated.zoomable.offset(transform.offset, animated = false)
                } finally { syncing = false }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            translated.zoomable.transformState.collect { transform ->
                if (syncing) return@collect
                syncing = true
                try {
                    original.zoomable.scale(transform.scaleX, animated = false)
                    original.zoomable.offset(transform.offset, animated = false)
                } finally { syncing = false }
            }
        }
    }

    companion object {
        const val KEY_TRANSLATED = "translated_path"
        const val KEY_ORIGINAL = "original_path"

        @JvmStatic
        fun newInstance(translatedPath: String, originalPath: String): MangaTranslationFragment {
            return MangaTranslationFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_TRANSLATED, translatedPath)
                    putString(KEY_ORIGINAL, originalPath)
                }
            }
        }
    }
}
