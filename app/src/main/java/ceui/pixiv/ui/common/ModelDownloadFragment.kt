package ceui.pixiv.ui.common

import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.FragmentRembgModelDownloadBinding
import ceui.lisa.fragments.BaseLazyFragment
import ceui.lisa.utils.Common
import ceui.pixiv.utils.setOnClick
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

abstract class ModelDownloadFragment : BaseLazyFragment<FragmentRembgModelDownloadBinding>() {

    private var downloadJob: Job? = null
    private var downloadStartTime = 0L
    private var lastSpeedUpdateTime = 0L
    private var lastSpeedBytes = 0L
    private var smoothedSpeed = 0.0
    private var lastUIUpdateTime = 0L

    protected abstract fun resolveModel(): DownloadableModel
    protected abstract fun getManager(): ModelDownloadManager
    @StringRes protected abstract fun titleRes(): Int
    @StringRes protected abstract fun subtitleRes(): Int
    @StringRes protected abstract fun doneTextRes(): Int

    private val model: DownloadableModel by lazy { resolveModel() }
    private var importController: ModelImportController? = null
    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        importController?.handlePickedUri(uri)
    }

    override fun initLayout() {
        mLayoutID = R.layout.fragment_rembg_model_download
    }




    override fun initData() {
        baseBind.toolbar.toolbarTitle.text = getString(titleRes())
        baseBind.toolbar.toolbar.setNavigationOnClickListener { activity?.finish() }

        baseBind.title.text = getString(titleRes())
        baseBind.subtitle.text = getString(subtitleRes(), model.displayName)
        baseBind.modelName.text = model.displayName
        baseBind.modelSizeBadge.text = model.sizeLabel
        baseBind.modelDesc.text = model.description

        if (getManager().isModelReady(requireContext(), model)) {
            showDoneState()
        } else {
            showInitState()
        }
    }

    private fun showInitState() {
        baseBind.progressArea.visibility = android.view.View.GONE
        baseBind.progressSizeText.visibility = android.view.View.GONE
        baseBind.statusText.visibility = android.view.View.GONE
        baseBind.btnPrimary.visibility = android.view.View.VISIBLE
        baseBind.btnPrimary.text = getString(R.string.string_rembg_model_start_download)
        baseBind.btnSecondary.visibility = android.view.View.GONE

        baseBind.btnPrimary.setOnClick { startDownload() }
        baseBind.btnImport.visibility = android.view.View.VISIBLE
        baseBind.btnImport.setOnClick { showCopyOrImportDialog() }
    }

    private fun showDownloadingState() {
        downloadStartTime = System.currentTimeMillis()
        lastSpeedUpdateTime = downloadStartTime
        lastSpeedBytes = 0L
        smoothedSpeed = 0.0

        baseBind.progressArea.visibility = android.view.View.VISIBLE

        baseBind.progressRing.isIndeterminate = true
        baseBind.progressPercent.text = "0%"
        baseBind.progressSizeText.visibility = android.view.View.VISIBLE
        baseBind.progressSizeText.text = getString(
            R.string.string_rembg_model_download_size, "0 MB", model.sizeLabel
        )
        baseBind.statusText.visibility = android.view.View.VISIBLE
        baseBind.statusText.text = getString(R.string.string_rembg_model_downloading)

        baseBind.btnPrimary.visibility = android.view.View.GONE
        baseBind.btnSecondary.visibility = android.view.View.VISIBLE
        baseBind.btnSecondary.text = getString(R.string.string_rembg_model_cancel)
        applyDefaultSecondaryColor()
        baseBind.btnSecondary.setOnClick { cancelDownload() }
        baseBind.btnImport.visibility = android.view.View.GONE
    }

    private fun showDoneState() {
        baseBind.progressArea.visibility = android.view.View.VISIBLE
        baseBind.progressRing.isIndeterminate = false
        baseBind.progressRing.setProgressCompat(100, true)
        baseBind.progressPercent.text = "100%"
        baseBind.progressSizeText.visibility = android.view.View.GONE
        baseBind.statusText.visibility = android.view.View.VISIBLE
        baseBind.statusText.text = getString(doneTextRes())

        baseBind.btnPrimary.visibility = android.view.View.VISIBLE
        baseBind.btnPrimary.text = getString(R.string.string_rembg_model_start_use)
        baseBind.btnPrimary.setOnClick { activity?.finish() }

        baseBind.btnSecondary.visibility = android.view.View.VISIBLE
        baseBind.btnSecondary.text = getString(R.string.string_rembg_model_delete)
        baseBind.btnSecondary.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.buttonTextRed)
        )
        baseBind.btnSecondary.setOnClick { confirmDeleteModel() }
        baseBind.btnImport.visibility = android.view.View.GONE
    }

    private fun applyDefaultSecondaryColor() {
        val ctx = context ?: return
        baseBind.btnSecondary.setTextColor(
            ContextCompat.getColor(ctx, R.color.secondary_text_color)
        )
    }

    private fun confirmDeleteModel() {
        val ctx = context ?: return
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.string_rembg_model_delete_confirm_title)
            .setMessage(getString(R.string.string_rembg_model_delete_confirm_message, model.displayName))
            .addAction(0, getString(R.string.string_cancel), QMUIDialogAction.ACTION_PROP_NEUTRAL) { d, _ ->
                d.dismiss()
            }
            .addAction(0, getString(R.string.string_rembg_model_delete), QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                deleteModelAndReset()
            }
            .show()
    }

    private fun deleteModelAndReset() {
        downloadJob?.cancel()
        downloadJob = null
        val ctx = context ?: return
        getManager().deleteModel(ctx, model)
        if (!isAdded || view == null) return
        showInitState()
    }

    private fun showErrorState() {
        baseBind.progressRing.isIndeterminate = false
        baseBind.progressRing.setProgressCompat(0, false)
        baseBind.progressPercent.text = "--"
        baseBind.statusText.text = getString(R.string.string_rembg_model_download_failed)

        baseBind.btnPrimary.visibility = android.view.View.VISIBLE
        baseBind.btnPrimary.text = getString(R.string.string_rembg_model_retry)
        baseBind.btnPrimary.setOnClick { startDownload() }

        baseBind.btnSecondary.visibility = android.view.View.VISIBLE
        baseBind.btnSecondary.text = getString(R.string.string_cancel)
        applyDefaultSecondaryColor()
        baseBind.btnSecondary.setOnClick { activity?.finish() }
        baseBind.btnImport.visibility = android.view.View.GONE
    }

    private fun startDownload() {
        showDownloadingState()
        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val success = getManager().downloadModel(requireContext(), model) { bytesRead, totalBytes ->
                val now = System.currentTimeMillis()
                if (now - lastUIUpdateTime < 300) return@downloadModel
                lastUIUpdateTime = now
                val root = view ?: return@downloadModel
                root.post {
                    if (!isAdded || view == null) return@post
                    val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                    baseBind.progressRing.isIndeterminate = false
                    baseBind.progressRing.setProgressCompat(percent, true)
                    baseBind.progressPercent.text = "$percent%"
                    val readMB = String.format("%.1f MB", bytesRead / 1_048_576.0)
                    val totalMB = if (totalBytes > 0) {
                        String.format("%.1f MB", totalBytes / 1_048_576.0)
                    } else {
                        model.sizeLabel
                    }
                    baseBind.progressSizeText.text = getString(
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
                        baseBind.statusText.text = if (etaText != null) {
                            "$speedText · $etaText"
                        } else {
                            speedText
                        }
                    }
                }
            }
            if (!isAdded || view == null) return@launch
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

    private fun showCopyOrImportDialog() {
        importController = ModelImportController(this, importPicker, getManager(), model) { success ->
            if (!isAdded || view == null) return@ModelImportController
            if (success) {
                Common.showToast(getString(R.string.model_download_import_success))
                showDoneState()
            } else {
                Common.showToast(getString(R.string.model_download_import_invalid))
            }
        }
        importController?.showCopyOrImportDialog()
    }
}
