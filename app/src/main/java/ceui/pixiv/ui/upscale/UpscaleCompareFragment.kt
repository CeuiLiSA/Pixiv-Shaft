package ceui.pixiv.ui.upscale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import ceui.lisa.R
import ceui.lisa.databinding.FragmentUpscaleCompareBinding
import ceui.pixiv.ui.common.saveImageToGallery
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView
import com.google.android.material.tabs.TabLayoutMediator
import com.hjq.toast.ToastUtils
import java.io.File

class UpscaleCompareFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_upscale_compare, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentUpscaleCompareBinding.bind(view)
        val upscaledPath = arguments?.getString(KEY_UPSCALED) ?: return
        val originalPath = arguments?.getString(KEY_ORIGINAL) ?: return

        binding.viewPager.adapter = CompareAdapter(requireActivity(), upscaledPath, originalPath)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == 0) "增强后" else "原图"
        }.attach()
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

class CompareAdapter(
    activity: FragmentActivity,
    private val upscaledPath: String,
    private val originalPath: String
) : FragmentStateAdapter(activity) {

    override fun getItemCount() = 2

    override fun createFragment(position: Int): Fragment {
        val path = if (position == 0) upscaledPath else originalPath
        val label = if (position == 0) "upscaled" else "original"
        return UpscalePageFragment.newInstance(path, label)
    }
}

class UpscalePageFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_upscale_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val path = arguments?.getString("path") ?: return
        val label = arguments?.getString("label") ?: "image"
        val zoomImage = view.findViewById<SketchZoomImageView>(R.id.zoom_image)
        val loadingIndicator = view.findViewById<View>(R.id.loading_indicator)

        loadingIndicator.visibility = View.GONE
        zoomImage.loadImage(File(path))

        view.findViewById<TextView>(R.id.save_button).setOnClickListener {
            val file = File(path)
            if (file.exists()) {
                val fileName = "${label}_${System.currentTimeMillis()}.png"
                saveImageToGallery(requireContext(), file, fileName)
            }
        }
    }

    companion object {
        fun newInstance(path: String, label: String): UpscalePageFragment {
            return UpscalePageFragment().apply {
                arguments = Bundle().apply {
                    putString("path", path)
                    putString("label", label)
                }
            }
        }
    }
}
