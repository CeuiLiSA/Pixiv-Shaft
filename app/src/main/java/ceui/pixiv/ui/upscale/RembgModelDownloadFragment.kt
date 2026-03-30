package ceui.pixiv.ui.upscale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRembgModelDownloadBinding
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RembgModelDownloadFragment : Fragment() {

    private var _binding: FragmentRembgModelDownloadBinding? = null
    private val binding get() = _binding!!

    private var downloadJob: Job? = null
    private val model: RembgModel by lazy {
        val name = arguments?.getString(ARG_MODEL_NAME) ?: RembgModel.ISNET_ANIME.name
        RembgModel.values().first { it.name == name }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRembgModelDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.subtitle.text = getString(R.string.string_rembg_model_download_subtitle, model.displayName)
        binding.modelName.text = model.displayName
        binding.modelSizeBadge.text = model.sizeLabel
        binding.modelDesc.text = model.description

        binding.heroIcon.apply {
            scaleX = 0f; scaleY = 0f
            animate().scaleX(1f).scaleY(1f)
                .setDuration(600)
                .setInterpolator(OvershootInterpolator(2f))
                .setStartDelay(100)
                .start()
        }

        binding.modelCard.apply {
            alpha = 0f; translationY = 40f
            animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(200).start()
        }

        binding.btnPrimary.apply {
            alpha = 0f; translationY = 30f
            animate().alpha(1f).translationY(0f).setDuration(400).setStartDelay(350).start()
        }

        if (RembgModelManager.isModelReady(requireContext(), model)) {
            showDoneState()
        } else {
            showInitState()
        }
    }

    private fun showInitState() {
        binding.progressArea.visibility = View.GONE
        binding.progressSizeText.visibility = View.GONE
        binding.statusText.visibility = View.GONE
        binding.btnPrimary.text = getString(R.string.string_rembg_model_start_download)
        binding.btnSecondary.visibility = View.GONE

        binding.btnPrimary.setOnClick { startDownload() }
    }

    private fun showDownloadingState() {
        binding.progressArea.visibility = View.VISIBLE
        binding.progressArea.alpha = 0f
        binding.progressArea.animate().alpha(1f).setDuration(300).start()

        binding.progressRing.isIndeterminate = true
        binding.progressPercent.text = "0%"
        binding.progressSizeText.visibility = View.VISIBLE
        binding.progressSizeText.text = getString(
            R.string.string_rembg_model_download_size, "0 MB", model.sizeLabel
        )
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.string_rembg_model_downloading)

        binding.btnPrimary.visibility = View.GONE
        binding.btnSecondary.visibility = View.VISIBLE
        binding.btnSecondary.text = getString(R.string.string_rembg_model_cancel)
        binding.btnSecondary.setOnClick { cancelDownload() }
    }

    private fun showDoneState() {
        binding.progressArea.visibility = View.VISIBLE
        binding.progressRing.isIndeterminate = false
        binding.progressRing.setProgressCompat(100, true)
        binding.progressPercent.text = "100%"
        binding.progressSizeText.visibility = View.GONE
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.string_rembg_model_download_done)

        binding.btnPrimary.visibility = View.VISIBLE
        binding.btnPrimary.text = getString(R.string.string_rembg_model_start_use)
        binding.btnPrimary.alpha = 0f
        binding.btnPrimary.animate().alpha(1f).setDuration(300).start()
        binding.btnPrimary.setOnClick { activity?.finish() }

        binding.btnSecondary.visibility = View.GONE
    }

    private fun showErrorState() {
        binding.progressRing.isIndeterminate = false
        binding.progressRing.setProgressCompat(0, false)
        binding.progressPercent.text = "--"
        binding.statusText.text = getString(R.string.string_rembg_model_download_failed)

        binding.btnPrimary.visibility = View.VISIBLE
        binding.btnPrimary.text = getString(R.string.string_rembg_model_retry)
        binding.btnPrimary.setOnClick { startDownload() }

        binding.btnSecondary.visibility = View.VISIBLE
        binding.btnSecondary.text = getString(R.string.string_cancel)
        binding.btnSecondary.setOnClick { activity?.finish() }
    }

    private fun startDownload() {
        showDownloadingState()
        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val success = RembgModelManager.downloadModel(requireContext(), model) { bytesRead, totalBytes ->
                binding.root.post {
                    if (_binding == null) return@post
                    val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                    binding.progressRing.isIndeterminate = false
                    binding.progressRing.setProgressCompat(percent, true)
                    binding.progressPercent.text = "$percent%"
                    val readMB = String.format("%.1f MB", bytesRead / 1_048_576.0)
                    val totalMB = if (totalBytes > 0) {
                        String.format("%.1f MB", totalBytes / 1_048_576.0)
                    } else {
                        model.sizeLabel
                    }
                    binding.progressSizeText.text = getString(
                        R.string.string_rembg_model_download_size, readMB, totalMB
                    )
                }
            }
            if (success) {
                showDoneState()
            } else {
                showErrorState()
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        RembgModelManager.deleteModel(requireContext(), model)
        showInitState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_MODEL_NAME = "model_name"

        @JvmStatic
        fun newInstance(modelName: String): RembgModelDownloadFragment {
            return RembgModelDownloadFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME, modelName)
                }
            }
        }
    }
}
