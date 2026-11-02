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
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.imageloader.PageImageSourceResolver
import ceui.pixiv.imageloader.awaitFile
import ceui.lisa.view.SeamlessCircularProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ceui.pixiv.ui.navigation.TemplateRoute

class IllustAiHelper(
    private val fragment: Fragment,
    private val rootView: View,
    /**
     * 覆盖层在详情页是 ViewStub，首帧不建。
     * 默认空实现：调用方没接 ViewStub 时（经典页）行为完全不变。
     */
    private val ensureOverlay: () -> Unit = {},
    private val onAiStateChanged: ((pageIndex: Int) -> Unit)? = null,
    private val onAiProgress: ((pageIndex: Int, progress: Float, eta: Float, statusText: String?) -> Unit)? = null,
    private val onAiTaskCompleted: ((pageIndex: Int, isUpscale: Boolean) -> Unit)? = null,
) {
    /** 记录已在观察的任务，避免重复观察 */
    private val observedTasks = mutableSetOf<AiQueueItem>()
    private val observedIllusts = mutableSetOf<Long>()

    private val context: Context get() = fragment.requireContext()
    private val lifecycleOwner: LifecycleOwner get() = fragment.viewLifecycleOwner

    // 经典全局覆盖层属性（仅当 onAiStateChanged 为 null 的降级场景使用）
    private val overlayRoot: View get() = rootView.findViewById(R.id.ai_overlay_root)
    private val loadingState: View get() = rootView.findViewById(R.id.ai_loading_state)
    private val doneState: View get() = rootView.findViewById(R.id.ai_done_state)
    private val viewCompare: View get() = rootView.findViewById(R.id.ai_view_compare)
    private val dismiss: View get() = rootView.findViewById(R.id.ai_dismiss)
    private val progressRing: SeamlessCircularProgressIndicator get() = rootView.findViewById(R.id.ai_progress_ring)
    private val progressText: TextView get() = rootView.findViewById(R.id.ai_progress_text)
    private val statusText: TextView get() = rootView.findViewById(R.id.ai_status_text)
    private val etaText: TextView get() = rootView.findViewById(R.id.ai_eta_text)

    fun performRembg(illust: Illust, model: RembgModel, pageIndex: Int = 0) {
        val state = IllustAiStore.getOrCreate(illust.id, pageIndex)
        if (state.hasRembgResult) {
            if (onAiStateChanged == null) {
                openRembgHighlight(state.rembgResultPath, state.rembgOriginalPath)
            } else {
                Common.showToast(R.string.string_ai_rembg_already_completed)
            }
            return
        }
        if (state.rembgRunning || state.rembgStatus == UpscaleStatus.Queued) {
            Common.showToast(R.string.string_ai_task_already_running)
            return
        }
        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_LARGE) ?: return
        state.rembgStatus = UpscaleStatus.Preparing
        state.rembgProgress = 0f
        state.rembgStatusText = context.getString(R.string.string_ai_waiting_for_original_image)

        if (onAiStateChanged != null) {
            onAiStateChanged.invoke(pageIndex)
        } else {
            ensureOverlay()
            overlayRoot.visibility = View.VISIBLE
            loadingState.visibility = View.VISIBLE
            doneState.visibility = View.GONE
            overlayRoot.alpha = 0f
            overlayRoot.animate().alpha(1f).setDuration(300).start()
            statusText.text = state.rembgStatusText
            progressRing.isIndeterminate = true
            progressText.visibility = View.GONE
        }

        lifecycleOwner.lifecycleScope.launch {
            val file = try {
                PageImageSourceResolver.resolve(context, illust, pageIndex, imageUrl).awaitFile(context)
                    ?: error("no page image source: $imageUrl")
            } catch (e: CancellationException) {
                state.rembgStatus = UpscaleStatus.Idle
                onAiStateChanged?.invoke(pageIndex)
                throw e
            } catch (e: Exception) {
                state.rembgStatus = UpscaleStatus.Idle
                if (onAiStateChanged != null) {
                    onAiStateChanged.invoke(pageIndex)
                } else {
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                }
                Common.showToast(R.string.string_ai_rembg_failed)
                return@launch
            }

            val key = RembgTask.illustKey(illust.id, pageIndex)
            val task = RembgTaskPool.startTask(key, context, file, file.absolutePath, model)
            observeRembgTask(illust.id, pageIndex, task)
        }
    }

    private fun observeRembgTask(illustId: Long, pageIndex: Int, task: RembgTask) {
        if (!observedTasks.add(task)) return

        val state = IllustAiStore.getOrCreate(illustId, pageIndex)

        fun navigateToHighlight(): Boolean {
            val result = task.resultFile.value ?: return false
            openRembgHighlight(result.absolutePath, task.originalFilePath)
            return true
        }

        fun showDoneState() {
            loadingState.visibility = View.GONE
            doneState.visibility = View.VISIBLE
            overlayRoot.visibility = View.VISIBLE
            overlayRoot.alpha = 1f
        }

        if (onAiStateChanged == null) {
            ensureOverlay()
            viewCompare.setOnClickListener {
                if (navigateToHighlight()) {
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
        }

        task.status.observe(lifecycleOwner) { status ->
            // 离开页面期间同 key 可能已重试；旧 LiveData 的终态重放不再拥有这页状态。
            if (RembgTaskPool.getTask(task.taskKey) !== task) {
                observedTasks.remove(task)
                return@observe
            }
            when (status) {
                UpscaleStatus.Queued -> {
                    state.rembgStatus = UpscaleStatus.Queued
                    state.rembgStatusText = context.getString(R.string.string_ai_task_queued)
                    if (onAiStateChanged != null) {
                        onAiStateChanged.invoke(pageIndex)
                    } else {
                        ensureOverlay()
                        overlayRoot.visibility = View.VISIBLE
                        loadingState.visibility = View.VISIBLE
                        doneState.visibility = View.GONE
                        if (overlayRoot.alpha < 1f) {
                            overlayRoot.alpha = 0f
                            overlayRoot.animate().alpha(1f).setDuration(300).start()
                        }
                        statusText.text = state.rembgStatusText
                        progressRing.isIndeterminate = true
                        progressText.visibility = View.GONE
                        etaText.text = ""
                    }
                }
                UpscaleStatus.Running -> {
                    state.rembgStatus = UpscaleStatus.Running
                    state.rembgStatusText = context.getString(R.string.string_ai_rembg_running)
                    if (onAiStateChanged != null) {
                        onAiStateChanged.invoke(pageIndex)
                    } else {
                        overlayRoot.visibility = View.VISIBLE
                        loadingState.visibility = View.VISIBLE
                        doneState.visibility = View.GONE
                        if (overlayRoot.alpha < 1f) {
                            overlayRoot.alpha = 0f
                            overlayRoot.animate().alpha(1f).setDuration(300).start()
                        }
                        statusText.text = state.rembgStatusText
                    }
                }
                UpscaleStatus.Done -> {
                    state.rembgStatus = UpscaleStatus.Done
                    val result = task.resultFile.value
                    if (result != null) {
                        state.rembgResultPath = result.absolutePath
                        state.rembgOriginalPath = task.originalFilePath
                    }
                    if (onAiStateChanged != null) {
                        onAiStateChanged.invoke(pageIndex)
                        onAiTaskCompleted?.invoke(pageIndex, false)
                    } else {
                        if (fragment.isResumed && navigateToHighlight()) {
                            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                                overlayRoot.visibility = View.GONE
                            }.start()
                            removeTaskIfCurrent(task)
                        } else {
                            showDoneState()
                        }
                    }
                }
                UpscaleStatus.Failed -> {
                    observedTasks.remove(task)
                    state.rembgStatus = UpscaleStatus.Failed
                    if (onAiStateChanged != null) {
                        onAiStateChanged.invoke(pageIndex)
                    } else {
                        overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                            overlayRoot.visibility = View.GONE
                        }.start()
                    }
                    Common.showToast(R.string.string_ai_rembg_failed)
                    removeTaskIfCurrent(task)
                }
                else -> {}
            }
        }

        task.progress.observe(lifecycleOwner) { percent ->
            if (RembgTaskPool.getTask(task.taskKey) !== task) return@observe
            if (task.status.value != UpscaleStatus.Running && task.status.value != UpscaleStatus.Queued) return@observe
            state.rembgProgress = percent
            if (onAiProgress != null) {
                onAiProgress.invoke(pageIndex, percent, 0f, state.rembgStatusText)
            } else {
                val p = (percent * 100).toInt()
                progressRing.isIndeterminate = percent <= 0f
                progressText.visibility = if (percent > 0f) View.VISIBLE else View.GONE
                progressRing.setProgressCompat(p, true)
                progressText.text = context.getString(R.string.string_ai_task_percent, p)
            }
        }
    }

    fun performUpscale(illust: Illust, model: UpscaleModel = UpscaleModel.REAL_ESRGAN, pageIndex: Int = 0) {
        val state = IllustAiStore.getOrCreate(illust.id, pageIndex)
        if (state.hasUpscaleResult) {
            if (onAiStateChanged == null) {
                openUpscaleComparison(state.upscaleResultPath, state.upscaleOriginalPath)
            } else {
                Common.showToast(R.string.string_ai_upscale_already_completed)
            }
            return
        }
        if (state.upscaleRunning || state.upscaleStatus == UpscaleStatus.Queued) {
            Common.showToast(R.string.string_ai_task_already_running)
            return
        }
        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_LARGE) ?: return
        state.upscaleStatus = UpscaleStatus.Preparing
        state.upscaleProgress = 0f
        state.upscaleEta = 0f
        state.upscaleStatusText = context.getString(R.string.string_ai_waiting_for_original_image)

        if (onAiStateChanged != null) {
            onAiStateChanged.invoke(pageIndex)
        } else {
            ensureOverlay()
            overlayRoot.visibility = View.VISIBLE
            loadingState.visibility = View.VISIBLE
            doneState.visibility = View.GONE
            if (overlayRoot.alpha < 1f) {
                overlayRoot.alpha = 0f
                overlayRoot.animate().alpha(1f).setDuration(300).start()
            }
            statusText.text = state.upscaleStatusText
            progressRing.isIndeterminate = true
            progressText.visibility = View.GONE
        }

        lifecycleOwner.lifecycleScope.launch {
            val file = try {
                PageImageSourceResolver.resolve(context, illust, pageIndex, imageUrl).awaitFile(context)
                    ?: error("no page image source: $imageUrl")
            } catch (e: CancellationException) {
                state.upscaleStatus = UpscaleStatus.Idle
                onAiStateChanged?.invoke(pageIndex)
                throw e
            } catch (e: Exception) {
                state.upscaleStatus = UpscaleStatus.Idle
                if (onAiStateChanged != null) {
                    onAiStateChanged.invoke(pageIndex)
                } else {
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                }
                Common.showToast(R.string.string_ai_upscale_failed)
                return@launch
            }

            val key = UpscaleTask.illustKey(illust.id, pageIndex)
            val task = UpscaleTaskPool.startTask(key, context, file, file.absolutePath, model)
            observeUpscaleTask(illust.id, pageIndex, task)
        }
    }

    fun restoreAiTasksIfRunning(illustId: Long) {
        if (observedIllusts.add(illustId)) {
            IllustAiStore.changes.observe(lifecycleOwner) {
                restoreAiTasks(illustId)
                IllustAiStore.allPages(illustId).forEach { onAiStateChanged?.invoke(it.pageIndex) }
            }
        }
        restoreAiTasks(illustId)
    }

    private fun restoreAiTasks(illustId: Long) {
        val upscaleTasks = UpscaleTaskPool.findTasksForIllust(illustId)
        val rembgTasks = RembgTaskPool.findTasksForIllust(illustId)

        for ((pageIndex, task) in upscaleTasks) {
            // 重新准备原图时池里可能还保留上一轮 Done；不能用它覆盖新准备状态。
            if (IllustAiStore.get(illustId, pageIndex)?.upscaleStatus == UpscaleStatus.Preparing) continue
            when (task.status.value) {
                UpscaleStatus.Idle, UpscaleStatus.Running, UpscaleStatus.Queued -> observeUpscaleTask(illustId, pageIndex, task)
                UpscaleStatus.Done -> {
                    if (onAiStateChanged == null) {
                        observeUpscaleTask(illustId, pageIndex, task)
                        continue
                    }
                    val state = IllustAiStore.getOrCreate(illustId, pageIndex)
                    state.upscaleStatus = UpscaleStatus.Done
                    val result = task.resultFile.value
                    if (result != null) {
                        state.upscaleResultPath = result.absolutePath
                        state.upscaleOriginalPath = task.originalFilePath
                    }
                    onAiStateChanged?.invoke(pageIndex)
                }
                UpscaleStatus.Failed -> {
                    UpscaleTaskPool.removeTask(task.taskKey)
                }
                else -> {}
            }
        }

        for ((pageIndex, task) in rembgTasks) {
            if (IllustAiStore.get(illustId, pageIndex)?.rembgStatus == UpscaleStatus.Preparing) continue
            when (task.status.value) {
                UpscaleStatus.Idle, UpscaleStatus.Running, UpscaleStatus.Queued -> observeRembgTask(illustId, pageIndex, task)
                UpscaleStatus.Done -> {
                    if (onAiStateChanged == null) {
                        observeRembgTask(illustId, pageIndex, task)
                        continue
                    }
                    val state = IllustAiStore.getOrCreate(illustId, pageIndex)
                    state.rembgStatus = UpscaleStatus.Done
                    val result = task.resultFile.value
                    if (result != null) {
                        state.rembgResultPath = result.absolutePath
                        state.rembgOriginalPath = task.originalFilePath
                    }
                    onAiStateChanged?.invoke(pageIndex)
                }
                UpscaleStatus.Failed -> {
                    RembgTaskPool.removeTask(task.taskKey)
                }
                else -> {}
            }
        }

        // 原图准备处于 Preparing，此时尚未入池。由准备协程自己的失败/取消路径清理，
        // 不能在 onResume 根据“池中没有任务”判死，否则尚在下载的任务可被重复启动。
    }

    fun restoreUpscaleIfRunning(illustId: Long) = restoreAiTasksIfRunning(illustId)

    private fun observeUpscaleTask(illustId: Long, pageIndex: Int, task: UpscaleTask) {
        if (!observedTasks.add(task)) return

        val state = IllustAiStore.getOrCreate(illustId, pageIndex)

        fun navigateToCompare(): Boolean {
            val result = task.resultFile.value ?: return false
            openUpscaleComparison(result.absolutePath, task.originalFilePath)
            return true
        }

        fun showDoneState() {
            loadingState.visibility = View.GONE
            doneState.visibility = View.VISIBLE
            overlayRoot.visibility = View.VISIBLE
            overlayRoot.alpha = 1f
        }

        if (onAiStateChanged == null) {
            ensureOverlay()
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
        }

        task.status.observe(lifecycleOwner) { status ->
            if (UpscaleTaskPool.getTask(task.taskKey) !== task) {
                observedTasks.remove(task)
                return@observe
            }
            when (status) {
                UpscaleStatus.Queued -> {
                    state.upscaleStatus = UpscaleStatus.Queued
                    state.upscaleStatusText = context.getString(R.string.string_ai_task_queued)
                    if (onAiStateChanged != null) {
                        onAiStateChanged.invoke(pageIndex)
                    } else {
                        ensureOverlay()
                        overlayRoot.visibility = View.VISIBLE
                        loadingState.visibility = View.VISIBLE
                        doneState.visibility = View.GONE
                        if (overlayRoot.alpha < 1f) {
                            overlayRoot.alpha = 0f
                            overlayRoot.animate().alpha(1f).setDuration(300).start()
                        }
                        statusText.text = state.upscaleStatusText
                        progressRing.isIndeterminate = true
                        progressText.visibility = View.GONE
                        etaText.text = ""
                    }
                }
                UpscaleStatus.Running -> {
                    state.upscaleStatus = UpscaleStatus.Running
                    state.upscaleStatusText = context.getString(R.string.string_ai_upscale_running, task.model.displayName)
                    if (onAiStateChanged != null) {
                        onAiStateChanged.invoke(pageIndex)
                    } else {
                        overlayRoot.visibility = View.VISIBLE
                        loadingState.visibility = View.VISIBLE
                        doneState.visibility = View.GONE
                        if (overlayRoot.alpha < 1f) {
                            overlayRoot.alpha = 0f
                            overlayRoot.animate().alpha(1f).setDuration(300).start()
                        }
                        statusText.text = state.upscaleStatusText
                    }
                }
                UpscaleStatus.Done -> {
                    state.upscaleStatus = UpscaleStatus.Done
                    val result = task.resultFile.value
                    if (result != null) {
                        state.upscaleResultPath = result.absolutePath
                        state.upscaleOriginalPath = task.originalFilePath
                    }
                    if (onAiStateChanged != null) {
                        onAiStateChanged.invoke(pageIndex)
                        onAiTaskCompleted?.invoke(pageIndex, true)
                    } else {
                        if (fragment.isResumed && navigateToCompare()) {
                            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                                overlayRoot.visibility = View.GONE
                            }.start()
                            removeTaskIfCurrent(task)
                        } else {
                            showDoneState()
                        }
                    }
                }
                UpscaleStatus.Failed -> {
                    state.upscaleStatus = UpscaleStatus.Failed
                    if (onAiStateChanged != null) {
                        onAiStateChanged.invoke(pageIndex)
                    } else {
                        overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                            overlayRoot.visibility = View.GONE
                        }.start()
                    }
                    Common.showToast(R.string.string_ai_upscale_failed)
                    removeTaskIfCurrent(task)
                }
                else -> {}
            }
        }

        task.progress.observe(lifecycleOwner) { percent ->
            if (UpscaleTaskPool.getTask(task.taskKey) !== task) return@observe
            if (task.status.value != UpscaleStatus.Running && task.status.value != UpscaleStatus.Queued) return@observe
            state.upscaleProgress = percent
            if (onAiProgress != null) {
                onAiProgress.invoke(pageIndex, percent, task.eta.value ?: 0f, state.upscaleStatusText)
            } else {
                val p = (percent * 100).toInt()
                progressRing.isIndeterminate = percent <= 0f
                progressText.visibility = if (percent > 0f) View.VISIBLE else View.GONE
                progressRing.setProgressCompat(p, true)
                progressText.text = context.getString(R.string.string_ai_task_percent, p)
            }
        }

        task.eta.observe(lifecycleOwner) { eta ->
            if (UpscaleTaskPool.getTask(task.taskKey) !== task) return@observe
            if (task.status.value != UpscaleStatus.Running) return@observe
            state.upscaleEta = eta
            if (onAiProgress != null) {
                onAiProgress.invoke(pageIndex, task.progress.value ?: 0f, eta, state.upscaleStatusText)
            } else {
                val etaSec = (eta + 0.5f).toInt()
                etaText.text = if (etaSec > 0) context.getString(R.string.string_ai_task_eta_seconds, etaSec) else ""
            }
        }
    }

    fun openUpscaleComparison(upscaledPath: String?, originalPath: String?) {
        if (upscaledPath == null || originalPath == null) return
        val intent = Intent(context, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.UPSCALE_COMPARE.key)
        intent.putExtra("upscaled_path", upscaledPath)
        intent.putExtra("original_path", originalPath)
        fragment.startActivity(intent)
    }

    fun openRembgHighlight(rembgPath: String?, originalPath: String?) {
        if (rembgPath == null || originalPath == null) return
        val intent = Intent(context, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.REMBG_HIGHLIGHT.key)
        intent.putExtra("original_path", originalPath)
        intent.putExtra("rembg_path", rembgPath)
        fragment.startActivity(intent)
    }

    /** 旧页面的终态回调不能误删同 key 下已经由别处启动的新任务。 */
    private fun removeTaskIfCurrent(task: UpscaleTask) {
        if (UpscaleTaskPool.getTask(task.taskKey) === task) {
            UpscaleTaskPool.removeTask(task.taskKey)
        }
        observedTasks.remove(task)
    }

    private fun removeTaskIfCurrent(task: RembgTask) {
        if (RembgTaskPool.getTask(task.taskKey) === task) {
            RembgTaskPool.removeTask(task.taskKey)
        }
        observedTasks.remove(task)
    }
}
