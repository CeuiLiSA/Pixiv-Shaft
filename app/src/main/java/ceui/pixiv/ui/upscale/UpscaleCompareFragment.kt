package ceui.pixiv.ui.upscale

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

class UpscaleCompareFragment : Fragment() {

    private var syncing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_upscale_compare, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hide status bar + navigation bar for immersive compare
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        val upscaledPath = arguments?.getString(KEY_UPSCALED) ?: return
        val originalPath = arguments?.getString(KEY_ORIGINAL) ?: return

        val imageOriginal = view.findViewById<SketchZoomImageView>(R.id.image_original)
        val imageEnhanced = view.findViewById<SketchZoomImageView>(R.id.image_enhanced)

        imageOriginal.loadImage(File(originalPath))
        imageEnhanced.loadImage(File(upscaledPath))

        view.findViewById<TextView>(R.id.save_original).setOnClickListener {
            val file = File(originalPath)
            if (file.exists()) {
                saveImageToGallery(requireContext(), file, "original_${System.currentTimeMillis()}.png")
            }
        }

        view.findViewById<TextView>(R.id.save_enhanced).setOnClickListener {
            val file = File(upscaledPath)
            if (file.exists()) {
                saveImageToGallery(requireContext(), file, "upscaled_${System.currentTimeMillis()}.png")
            }
        }

        setupZoomSync(imageOriginal, imageEnhanced)
    }

    private fun setupZoomSync(original: SketchZoomImageView, enhanced: SketchZoomImageView) {
        viewLifecycleOwner.lifecycleScope.launch {
            original.zoomable.transformState.collect { transform ->
                if (syncing) return@collect
                syncing = true
                try {
                    enhanced.zoomable.scale(transform.scaleX, animated = false)
                    enhanced.zoomable.offset(transform.offset, animated = false)
                } finally { syncing = false }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            enhanced.zoomable.transformState.collect { transform ->
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
        const val KEY_UPSCALED = "upscaled_path"
        const val KEY_ORIGINAL = "original_path"

        @JvmStatic
        fun newInstance(upscaledPath: String, originalPath: String): UpscaleCompareFragment {
            return UpscaleCompareFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_UPSCALED, upscaledPath)
                    putString(KEY_ORIGINAL, originalPath)
                }
            }
        }
    }
}
