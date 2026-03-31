package ceui.pixiv.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRembgModelDownloadBinding
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

abstract class ModelDownloadFragment : Fragment() {

    private var _binding: FragmentRembgModelDownloadBinding? = null
    protected val binding get() = _binding!!

    private var downloadJob: Job? = null
    private var downloadStartTime = 0L
    private var lastSpeedUpdateTime = 0L
    private var lastSpeedBytes = 0L
    private var smoothedSpeed = 0.0

    protected abstract fun resolveModel(): DownloadableModel
    protected abstract fun getManager(): ModelDownloadManager
    @StringRes protected abstract fun titleRes(): Int
    @StringRes protected abstract fun subtitleRes(): Int
    @StringRes protected abstract fun doneTextRes(): Int

    private val model: DownloadableModel by lazy { resolveModel() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRembgModelDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.toolbarTitle.text = getString(titleRes())
        binding.toolbar.toolbar.setNavigationOnClickListener { activity?.finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, insets.top, v.paddingRight, 0)
            WindowInsetsCompat.CONSUMED
        }

        binding.title.text = getString(titleRes())
        binding.subtitle.text = getString(subtitleRes(), model.displayName)
        binding.modelName.text = model.displayName
        binding.modelSizeBadge.text = model.sizeLabel
        binding.modelDesc.text = model.description

        if (getManager().isModelReady(requireContext(), model)) {
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
        downloadStartTime = System.currentTimeMillis()
        lastSpeedUpdateTime = downloadStartTime
        lastSpeedBytes = 0L
        smoothedSpeed = 0.0

        binding.progressArea.visibility = View.VISIBLE

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
        binding.statusText.text = getString(doneTextRes())

        binding.btnPrimary.visibility = View.VISIBLE
        binding.btnPrimary.text = getString(R.string.string_rembg_model_start_use)
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
            val success = getManager().downloadModel(requireContext(), model) { bytesRead, totalBytes ->
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

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastSpeedUpdateTime
                    if (elapsed >= 500) {
                        val deltaBytes = bytesRead - lastSpeedBytes
                        val instantSpeed = deltaBytes.toDouble() / elapsed * 1000
                        smoothedSpeed = if (smoothedSpeed == 0.0) {
                            instantSpeed
                        } else {
                            smoothedSpeed * 0.3 + instantSpeed * 0.7
                        }
                        lastSpeedUpdateTime = now
                        lastSpeedBytes = bytesRead

                        val speedText = formatSpeed(smoothedSpeed)
                        val etaText = if (totalBytes > 0 && smoothedSpeed > 0) {
                            val remaining = (totalBytes - bytesRead) / smoothedSpeed
                            formatEta(remaining)
                        } else {
                            null
                        }
                        binding.statusText.text = if (etaText != null) {
                            "$speedText · $etaText"
                        } else {
                            speedText
                        }
                    }
                }
            }
            if (success) {
                showDoneState()
            } else {
                showErrorState()
            }
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return if (bytesPerSecond >= 1_048_576) {
            String.format("%.1f MB/s", bytesPerSecond / 1_048_576)
        } else {
            String.format("%.0f KB/s", bytesPerSecond / 1024)
        }
    }

    private fun formatEta(seconds: Double): String {
        val s = seconds.toInt()
        return when {
            s < 5 -> "即将完成"
            s < 60 -> "约 ${s}秒"
            s < 3600 -> "约 ${s / 60}分${s % 60}秒"
            else -> "约 ${s / 3600}小时"
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        getManager().deleteModel(requireContext(), model)
        showInitState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
