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
import ceui.loxia.Illust
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.imageloader.ImageLoaderV3
import ceui.lisa.view.SeamlessCircularProgressIndicator
import kotlinx.coroutines.launch

class IllustAiHelper(
    private val fragment: Fragment,
    private val rootView: View
) {
    /** 同一任务可能被“恢复任务”和重复点击同时接入；每个 view 生命周期只观察一次。 */
    private var observedUpscaleTask: UpscaleTask? = null

    private val context: Context get() = fragment.requireContext()
    private val lifecycleOwner: LifecycleOwner get() = fragment.viewLifecycleOwner

    private val overlayRoot: View get() = rootView.findViewById(R.id.ai_overlay_root)
    private val loadingState: View get() = rootView.findViewById(R.id.ai_loading_state)
    private val doneState: View get() = rootView.findViewById(R.id.ai_done_state)
    private val viewCompare: View get() = rootView.findViewById(R.id.ai_view_compare)
    private val dismiss: View get() = rootView.findViewById(R.id.ai_dismiss)
    private val progressRing: SeamlessCircularProgressIndicator get() = rootView.findViewById(R.id.ai_progress_ring)
    private val progressText: TextView get() = rootView.findViewById(R.id.ai_progress_text)
    private val statusText: TextView get() = rootView.findViewById(R.id.ai_status_text)
    private val etaText: TextView get() = rootView.findViewById(R.id.ai_eta_text)

    fun performRembg(illust: Illust, model: RembgModel) {
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

        // 复用详情页(IllustAdapter)已加载的原图,与显示层同一共享任务,不重新下载。
        val task = ImageLoaderV3.obtain(imageUrl)
        lifecycleOwner.lifecycleScope.launch {
            val file = try {
                task.awaitFile()
            } catch (e: Exception) {
                overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                    overlayRoot.visibility = View.GONE
                }.start()
                Common.showToast(R.string.string_ai_rembg_failed)
                return@launch
            }
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


    fun performUpscale(illust: Illust, model: UpscaleModel = UpscaleModel.REAL_ESRGAN) {
        val imageUrl = IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_LARGE) ?: return

        // 复用详情页(IllustAdapter)已加载的原图,与显示层同一共享任务,不重新下载。
        val loadTask = ImageLoaderV3.obtain(imageUrl)
        lifecycleOwner.lifecycleScope.launch {
            val file = try { loadTask.awaitFile() } catch (e: Exception) { return@launch }
            val key = UpscaleTask.illustKey(illust.id)
            val task = UpscaleTaskPool.startTask(key, context, file, file.absolutePath, model)
            observeUpscaleTask(task)
        }
    }

    fun restoreUpscaleIfRunning(illustId: Int) {
        val key = UpscaleTask.illustKey(illustId.toLong())
        val task = UpscaleTaskPool.getTask(key) ?: return
        when (task.status.value) {
            UpscaleStatus.Running, UpscaleStatus.Done -> observeUpscaleTask(task)
            UpscaleStatus.Failed -> {
                Common.showToast(R.string.string_ai_upscale_failed)
                removeTaskIfCurrent(task)
            }
            else -> {}
        }
    }

    private fun observeUpscaleTask(task: UpscaleTask) {
        if (observedUpscaleTask === task) return
        observedUpscaleTask = task

        fun navigateToCompare(): Boolean {
            val result = task.resultFile.value ?: return false
            val intent = Intent(context, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画质增强对比")
            intent.putExtra("upscaled_path", result.absolutePath)
            intent.putExtra("original_path", task.originalFilePath)
            fragment.startActivity(intent)
            return true
        }

        fun showDoneState() {
            loadingState.visibility = View.GONE
            doneState.visibility = View.VISIBLE
            overlayRoot.visibility = View.VISIBLE
            overlayRoot.alpha = 1f
        }

        viewCompare.setOnClickListener {
            if (navigateToCompare()) {
                overlayRoot.visibility = View.GONE
                removeTaskIfCurrent(task)
            }
        }
        dismiss.setOnClickListener {
            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                overlayRoot.visibility = View.GONE
            }.start()
            removeTaskIfCurrent(task)
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
                    if (fragment.isResumed && navigateToCompare()) {
                        overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                            overlayRoot.visibility = View.GONE
                        }.start()
                        // 自动打开对比页后任务已完成使命。若留在池里，返回详情再旋转会把 Done
                        // 任务恢复出来并二次跳转，同时结果文件引用也会被进程长期持有。
                        removeTaskIfCurrent(task)
                    } else {
                        showDoneState()
                    }
                }
                UpscaleStatus.Failed -> {
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                    Common.showToast(R.string.string_ai_upscale_failed)
                    removeTaskIfCurrent(task)
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

    /** 旧页面的终态回调不能误删同 key 下已经由别处启动的新任务。 */
    private fun removeTaskIfCurrent(task: UpscaleTask) {
        if (UpscaleTaskPool.getTask(task.taskKey) === task) {
            UpscaleTaskPool.removeTask(task.taskKey)
        }
        if (observedUpscaleTask === task) {
            observedUpscaleTask = null
        }
    }
}
