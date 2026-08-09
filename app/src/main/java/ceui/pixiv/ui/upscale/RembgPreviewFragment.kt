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
import ceui.lisa.R
import ceui.pixiv.ui.common.saveImageToGallery
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView
import java.io.File

class RembgPreviewFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_rembg_preview, container, false)
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

        val path = arguments?.getString(KEY_PATH) ?: return

        val imagePreview = view.findViewById<SketchZoomImageView>(R.id.image_preview)
        imagePreview.loadImage(File(path))

        view.findViewById<TextView>(R.id.btn_save).setOnClickListener {
            val file = File(path)
            if (file.exists()) {
                saveImageToGallery(requireContext(), file, "rembg_${System.currentTimeMillis()}.png")
            }
        }
    }

    companion object {
        const val KEY_PATH = "rembg_path"

        @JvmStatic
        fun newInstance(path: String): RembgPreviewFragment {
            return RembgPreviewFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_PATH, path)
                }
            }
        }
    }
}
