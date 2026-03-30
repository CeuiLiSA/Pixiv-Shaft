package ceui.pixiv.ui.upscale

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch

class IllustAiHelper(
    private val fragment: Fragment,
    private val rootView: View
) {
    private val context: Context get() = fragment.requireContext()
    private val lifecycleOwner: LifecycleOwner get() = fragment.viewLifecycleOwner

    private val overlayRoot: View get() = rootView.findViewById(R.id.ai_overlay_root)
    private val loadingState: View get() = rootView.findViewById(R.id.ai_loading_state)
    private val doneState: View get() = rootView.findViewById(R.id.ai_done_state)
    private val viewCompare: View get() = rootView.findViewById(R.id.ai_view_compare)
    private val dismiss: View get() = rootView.findViewById(R.id.ai_dismiss)
    private val progressRing: CircularProgressIndicator get() = rootView.findViewById(R.id.ai_progress_ring)
    private val progressText: TextView get() = rootView.findViewById(R.id.ai_progress_text)
    private val statusText: TextView get() = rootView.findViewById(R.id.ai_status_text)
    private val etaText: TextView get() = rootView.findViewById(R.id.ai_eta_text)

    fun performRembg(illust: IllustsBean, model: RembgModel) {
        val imageUrl = IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_LARGE) ?: return

        overlayRoot.visibility = View.VISIBLE
        loadingState.visibility = View.VISIBLE
        doneState.visibility = View.GONE
        overlayRoot.alpha = 0f
        overlayRoot.animate().alpha(1f).setDuration(300).start()
        statusText.text = context.getString(R.string.string_ai_rembg_running)
        progressRing.isIndeterminate = true
        progressText.visibility = View.GONE

        val loadTask = TaskPool.getLoadTask(NamedUrl("", imageUrl))
        loadTask.result.observe(lifecycleOwner) { file ->
            if (file != null) {
                lifecycleOwner.lifecycleScope.launch {
                    val result = BackgroundRemover.removeBackground(context, file, model) { percent ->
                        rootView.post {
                            progressRing.isIndeterminate = false
                            progressText.visibility = View.VISIBLE
                            val p = (percent * 100).toInt()
                            progressRing.setProgressCompat(p, true)
                            progressText.text = "$p%"
                        }
                    }
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                    if (result != null) {
                        val intent = Intent(context, TemplateActivity::class.java)
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "主体高亮")
                        intent.putExtra("original_path", file.absolutePath)
                        intent.putExtra("rembg_path", result.absolutePath)
                        fragment.startActivity(intent)
                    } else {
                        Common.showToast(R.string.string_ai_rembg_failed)
                    }
                }
            }
        }
    }

    fun performOcr(illust: IllustsBean) {
        val imageUrl = IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_LARGE) ?: return

        Common.showToast(R.string.string_ai_ocr_running)
        val loadTask = TaskPool.getLoadTask(NamedUrl("", imageUrl))
        loadTask.result.observe(lifecycleOwner) { file ->
            if (file != null) {
                lifecycleOwner.lifecycleScope.launch {
                    val results = MangaOcr.recognize(context, file)
                    if (results != null && results.isNotEmpty()) {
                        val texts = ArrayList(results.map { it.text })
                        val intent = Intent(context, TemplateActivity::class.java)
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "OCR结果")
                        intent.putStringArrayListExtra("ocr_texts", texts)
                        fragment.startActivity(intent)
                    } else if (results != null && results.isEmpty()) {
                        Common.showToast(R.string.string_ai_ocr_empty)
                    } else {
                        Common.showToast(R.string.string_ai_ocr_failed)
                    }
                }
            }
        }
    }

    fun performUpscale(illust: IllustsBean, model: UpscaleModel = UpscaleModel.REAL_ESRGAN) {
        val imageUrl = IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_LARGE) ?: return

        val loadTask = TaskPool.getLoadTask(NamedUrl("", imageUrl))
        loadTask.result.observe(lifecycleOwner) { file ->
            if (file != null) {
                val key = UpscaleTask.illustKey(illust.id)
                val task = UpscaleTaskPool.startTask(key, context, file, file.absolutePath, model)
                observeUpscaleTask(task)
            }
        }
    }

    fun restoreUpscaleIfRunning(illustId: Int) {
        val key = UpscaleTask.illustKey(illustId)
        val task = UpscaleTaskPool.getTask(key) ?: return
        when (task.status.value) {
            UpscaleStatus.Running, UpscaleStatus.Done -> observeUpscaleTask(task)
            UpscaleStatus.Failed -> {
                Common.showToast(R.string.string_ai_upscale_failed)
                UpscaleTaskPool.removeTask(key)
            }
            else -> {}
        }
    }

    private fun observeUpscaleTask(task: UpscaleTask) {
        fun navigateToCompare() {
            val result = task.resultFile.value ?: return
            val intent = Intent(context, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画质增强对比")
            intent.putExtra("upscaled_path", result.absolutePath)
            intent.putExtra("original_path", task.originalFilePath)
            fragment.startActivity(intent)
        }

        fun showDoneState() {
            loadingState.visibility = View.GONE
            doneState.visibility = View.VISIBLE
            overlayRoot.visibility = View.VISIBLE
            overlayRoot.alpha = 1f
        }

        viewCompare.setOnClickListener {
            navigateToCompare()
            overlayRoot.visibility = View.GONE
            UpscaleTaskPool.removeTask(task.taskKey)
        }
        dismiss.setOnClickListener {
            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                overlayRoot.visibility = View.GONE
            }.start()
            UpscaleTaskPool.removeTask(task.taskKey)
        }

        task.status.observe(lifecycleOwner) { status ->
            when (status) {
                UpscaleStatus.Running -> {
                    overlayRoot.visibility = View.VISIBLE
                    loadingState.visibility = View.VISIBLE
                    doneState.visibility = View.GONE
                    if (overlayRoot.alpha < 1f) {
                        overlayRoot.alpha = 0f
                        overlayRoot.animate().alpha(1f).setDuration(300).start()
                    }
                    statusText.text = context.getString(R.string.string_ai_upscale_running, task.model.displayName)
                }
                UpscaleStatus.Done -> {
                    if (fragment.isResumed) {
                        overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                            overlayRoot.visibility = View.GONE
                        }.start()
                        navigateToCompare()
                    } else {
                        showDoneState()
                    }
                }
                UpscaleStatus.Failed -> {
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                    Common.showToast(R.string.string_ai_upscale_failed)
                    UpscaleTaskPool.removeTask(task.taskKey)
                }
                else -> {}
            }
        }
        task.progress.observe(lifecycleOwner) { percent ->
            val p = (percent * 100).toInt()
            progressRing.setProgressCompat(p, true)
            progressText.text = "$p%"
        }
        task.eta.observe(lifecycleOwner) { eta ->
            etaText.text = if (eta > 0) "预计 ${String.format("%.0f", eta)} 秒后完成" else ""
        }
    }
}
