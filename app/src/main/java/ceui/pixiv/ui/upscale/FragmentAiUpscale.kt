package ceui.pixiv.ui.upscale

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import java.io.FileOutputStream

class FragmentAiUpscale : Fragment() {

    private var selectedFile: File? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var currentTaskKey: String? = null
    private var selectedModel: UpscaleModel = UpscaleModel.REAL_CUGAN

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onImagePicked(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai_upscale, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val emptyState = view.findViewById<LinearLayout>(R.id.pick_empty_state)
        val previewState = view.findViewById<LinearLayout>(R.id.preview_state)
        val imagePreview = view.findViewById<ImageView>(R.id.image_preview)
        val resolutionText = view.findViewById<TextView>(R.id.resolution_text)
        val btnPick = view.findViewById<TextView>(R.id.btn_pick_image)
        val btnRepick = view.findViewById<TextView>(R.id.btn_repick)
        val btnStart = view.findViewById<TextView>(R.id.btn_start)

        // Model selector chips
        val chipEsrgan = view.findViewById<LinearLayout>(R.id.chip_esrgan)
        val chipCugan = view.findViewById<LinearLayout>(R.id.chip_cugan)
        val chipEsrganTitle = view.findViewById<TextView>(R.id.chip_esrgan_title)
        val chipEsrganDesc = view.findViewById<TextView>(R.id.chip_esrgan_desc)
        val chipCuganTitle = view.findViewById<TextView>(R.id.chip_cugan_title)
        val chipCuganDesc = view.findViewById<TextView>(R.id.chip_cugan_desc)

        fun updateChipSelection() {
            val isEsrgan = selectedModel == UpscaleModel.REAL_ESRGAN
            chipEsrgan.setBackgroundResource(
                if (isEsrgan) R.drawable.bg_model_chip_selected else R.drawable.bg_model_chip_unselected
            )
            chipCugan.setBackgroundResource(
                if (!isEsrgan) R.drawable.bg_model_chip_selected else R.drawable.bg_model_chip_unselected
            )
            chipEsrganTitle.setTextColor(
                if (isEsrgan) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
            )
            chipEsrganDesc.setTextColor(
                if (isEsrgan) 0x66FFFFFF.toInt() else 0x40FFFFFF.toInt()
            )
            chipCuganTitle.setTextColor(
                if (!isEsrgan) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
            )
            chipCuganDesc.setTextColor(
                if (!isEsrgan) 0x66FFFFFF.toInt() else 0x40FFFFFF.toInt()
            )
        }

        chipEsrgan.setOnClickListener {
            selectedModel = UpscaleModel.REAL_ESRGAN
            updateChipSelection()
        }
        chipCugan.setOnClickListener {
            selectedModel = UpscaleModel.REAL_CUGAN
            updateChipSelection()
        }

        fun launchPicker() {
            pickImage.launch("image/*")
        }

        btnPick.setOnClickListener { launchPicker() }
        btnRepick.setOnClickListener { launchPicker() }
        emptyState.setOnClickListener { launchPicker() }

        btnStart.setOnClickListener {
            val file = selectedFile ?: return@setOnClickListener
            startUpscale(file, view)
        }

        // Entrance animations
        val imageCard = view.findViewById<FrameLayout>(R.id.image_card)
        imageCard.alpha = 0f
        imageCard.translationY = 40f
        imageCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(150)
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()

        btnStart.alpha = 0f
        btnStart.translationY = 30f
        btnStart.animate()
            .alpha(0.35f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(300)
            .start()

        // Restore running task
        restoreIfRunning(view)
    }

    private fun onImagePicked(uri: Uri) {
        val view = view ?: return
        val ctx = requireContext()

        val emptyState = view.findViewById<LinearLayout>(R.id.pick_empty_state)
        val previewState = view.findViewById<LinearLayout>(R.id.preview_state)
        val imagePreview = view.findViewById<ImageView>(R.id.image_preview)
        val resolutionText = view.findViewById<TextView>(R.id.resolution_text)
        val btnStart = view.findViewById<TextView>(R.id.btn_start)

        // Copy URI to a temp file
        val tempFile = File(ctx.cacheDir, "gallery_pick_${System.currentTimeMillis()}.png")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Common.showToast(R.string.string_ai_upscale_failed)
            return
        }

        // Decode dimensions
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, opts)
        imageWidth = opts.outWidth
        imageHeight = opts.outHeight

        if (imageWidth <= 0 || imageHeight <= 0) {
            Common.showToast(R.string.string_ai_upscale_failed)
            tempFile.delete()
            return
        }

        selectedFile = tempFile

        // Update UI: show preview
        emptyState.visibility = View.GONE
        previewState.visibility = View.VISIBLE
        imagePreview.setImageURI(Uri.fromFile(tempFile))

        resolutionText.text = getString(
            R.string.string_ai_upscale_resolution,
            imageWidth, imageHeight,
            imageWidth * 2, imageHeight * 2
        )

        // Enable start button with animation
        btnStart.animate()
            .alpha(1f)
            .setDuration(300)
            .withStartAction {
                btnStart.isClickable = true
                btnStart.background = resources.getDrawable(R.drawable.bg_upscale_start_button, null)
                btnStart.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
                    requireContext(), R.animator.button_press_alpha
                )
            }
            .start()

        // Reset overlay if visible from previous run
        view.findViewById<View>(R.id.ai_overlay_root).visibility = View.GONE
    }

    private fun startUpscale(inputFile: File, rootView: View) {
        val btnStart = rootView.findViewById<TextView>(R.id.btn_start)

        // Disable start button
        btnStart.isClickable = false
        btnStart.animate().alpha(0.35f).setDuration(200).start()
        btnStart.background = resources.getDrawable(R.drawable.bg_upscale_start_button_disabled, null)
        btnStart.stateListAnimator = null

        val key = UpscaleTask.galleryKey()
        currentTaskKey = key
        val task = UpscaleTaskPool.startTask(key, requireContext(), inputFile, inputFile.absolutePath, selectedModel)

        observeTask(task, rootView, autoNavigate = true)
    }

    private fun enableStartButton(rootView: View) {
        val btnStart = rootView.findViewById<TextView>(R.id.btn_start)
        btnStart.isClickable = true
        btnStart.animate().alpha(1f).setDuration(300).start()
        btnStart.background = resources.getDrawable(R.drawable.bg_upscale_start_button, null)
        btnStart.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
            requireContext(), R.animator.button_press_alpha
        )
    }

    private fun observeTask(task: UpscaleTask, rootView: View, autoNavigate: Boolean) {
        val overlayRoot = rootView.findViewById<View>(R.id.ai_overlay_root)
        val loadingState = rootView.findViewById<View>(R.id.ai_loading_state)
        val doneState = rootView.findViewById<View>(R.id.ai_done_state)
        val viewCompare = rootView.findViewById<View>(R.id.ai_view_compare)
        val dismiss = rootView.findViewById<View>(R.id.ai_dismiss)
        val progressRing = rootView.findViewById<CircularProgressIndicator>(R.id.ai_progress_ring)
        val progressText = rootView.findViewById<TextView>(R.id.ai_progress_text)
        val statusText = rootView.findViewById<TextView>(R.id.ai_status_text)
        val etaText = rootView.findViewById<TextView>(R.id.ai_eta_text)

        viewCompare.setOnClickListener {
            val result = task.resultFile.value ?: return@setOnClickListener
            navigateToCompare(result.absolutePath, task.originalFilePath)
            overlayRoot.visibility = View.GONE
            UpscaleTaskPool.removeTask(task.taskKey)
            currentTaskKey = null
            enableStartButton(rootView)
        }
        dismiss.setOnClickListener {
            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                overlayRoot.visibility = View.GONE
            }.start()
            UpscaleTaskPool.removeTask(task.taskKey)
            currentTaskKey = null
            enableStartButton(rootView)
        }

        progressRing.isIndeterminate = false

        task.status.observe(viewLifecycleOwner) { status ->
            when (status) {
                UpscaleStatus.Running -> {
                    overlayRoot.visibility = View.VISIBLE
                    loadingState.visibility = View.VISIBLE
                    doneState.visibility = View.GONE
                    if (overlayRoot.alpha < 1f) {
                        overlayRoot.alpha = 0f
                        overlayRoot.animate().alpha(1f).setDuration(300).start()
                    }
                    statusText.text = getString(R.string.string_ai_upscale_running)
                }
                UpscaleStatus.Done -> {
                    if (autoNavigate) {
                        val result = task.resultFile.value
                        if (result != null) {
                            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                                overlayRoot.visibility = View.GONE
                            }.start()
                            navigateToCompare(result.absolutePath, task.originalFilePath)
                        }
                        UpscaleTaskPool.removeTask(task.taskKey)
                        currentTaskKey = null
                        enableStartButton(rootView)
                    } else {
                        loadingState.visibility = View.GONE
                        doneState.visibility = View.VISIBLE
                        overlayRoot.visibility = View.VISIBLE
                        overlayRoot.alpha = 1f
                    }
                }
                UpscaleStatus.Failed -> {
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                    Common.showToast(R.string.string_ai_upscale_failed)
                    UpscaleTaskPool.removeTask(task.taskKey)
                    currentTaskKey = null
                    if (selectedFile != null) {
                        enableStartButton(rootView)
                    }
                }
                else -> {}
            }
        }

        task.progress.observe(viewLifecycleOwner) { percent ->
            val p = (percent * 100).toInt()
            progressRing.setProgressCompat(p, true)
            progressText.text = "$p%"
        }
        task.eta.observe(viewLifecycleOwner) { eta ->
            etaText.text = if (eta > 0) "预计 ${String.format("%.0f", eta)} 秒后完成" else ""
        }
    }

    private fun navigateToCompare(upscaledPath: String, originalPath: String) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画质增强对比")
        intent.putExtra("upscaled_path", upscaledPath)
        intent.putExtra("original_path", originalPath)
        startActivity(intent)
    }

    private fun restoreIfRunning(rootView: View) {
        val key = currentTaskKey ?: return
        val task = UpscaleTaskPool.getTask(key) ?: return
        when (task.status.value) {
            UpscaleStatus.Running, UpscaleStatus.Done -> {
                observeTask(task, rootView, autoNavigate = false)
            }
            UpscaleStatus.Failed -> {
                Common.showToast(R.string.string_ai_upscale_failed)
                UpscaleTaskPool.removeTask(key)
                currentTaskKey = null
            }
            else -> {}
        }
    }
}
