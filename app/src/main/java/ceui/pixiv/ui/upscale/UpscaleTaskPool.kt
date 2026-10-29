package ceui.pixiv.ui.upscale

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

enum class UpscaleStatus { Idle, Queued, Running, Done, Failed }

private fun parseIllustPageKey(key: String): Pair<Long, Int>? {
    val parts = key.split('_')
    if (parts.size >= 3 && (parts[0] == "illust" || parts[0] == "rembg")) {
        val id = parts[1].toLongOrNull() ?: return null
        val page = parts[2].toIntOrNull() ?: return null
        return id to page
    }
    return null
}

interface AiQueueItem {
    val taskKey: String
    val status: LiveData<UpscaleStatus>
    fun markQueued()
    fun execute()
}

object AiTaskQueue {
    private val queue = ArrayDeque<AiQueueItem>()
    private var runningTask: AiQueueItem? = null
    private val lock = Any()

    fun enqueue(task: AiQueueItem) {
        var shouldExecute = false
        synchronized(lock) {
            if (runningTask == null) {
                runningTask = task
                shouldExecute = true
            } else {
                task.markQueued()
                if (!queue.contains(task)) {
                    queue.addLast(task)
                }
            }
        }
        if (shouldExecute) {
            task.execute()
        }
    }

    fun onTaskFinished(task: AiQueueItem) {
        var nextTask: AiQueueItem? = null
        synchronized(lock) {
            if (runningTask === task) {
                runningTask = null
            }
            queue.remove(task)
            if (runningTask == null && queue.isNotEmpty()) {
                nextTask = queue.removeFirst()
                runningTask = nextTask
            }
        }
        nextTask?.execute()
    }

    fun remove(taskKey: String) {
        var nextTask: AiQueueItem? = null
        synchronized(lock) {
            queue.removeAll { it.taskKey == taskKey }
            if (runningTask?.taskKey == taskKey) {
                runningTask = null
                if (queue.isNotEmpty()) {
                    nextTask = queue.removeFirst()
                    runningTask = nextTask
                }
            }
        }
        nextTask?.execute()
    }
}

class UpscaleTask(
    override val taskKey: String,
    private val context: Context,
    private val inputFile: File,
    private val originalPath: String,
    val model: UpscaleModel = UpscaleModel.REAL_ESRGAN
) : AiQueueItem {
    private val _progress = MutableLiveData(0f)
    val progress: LiveData<Float> = _progress
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _eta = MutableLiveData(0f)
    val eta: LiveData<Float> = _eta

    private val _status = MutableLiveData(UpscaleStatus.Idle)
    override val status: LiveData<UpscaleStatus> = _status

    private val _resultFile = MutableLiveData<File?>()
    val resultFile: LiveData<File?> = _resultFile

    val originalFilePath: String get() = originalPath

    override fun markQueued() {
        if (_status.value == UpscaleStatus.Running || _status.value == UpscaleStatus.Done) return
        _status.postValue(UpscaleStatus.Queued)
        val illustPage = parseIllustPageKey(taskKey)
        if (illustPage != null) {
            val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
            state.upscaleStatus = UpscaleStatus.Queued
            state.upscaleProgress = 0f
            state.upscaleEta = 0f
            state.upscaleStatusText = context.getString(ceui.lisa.R.string.string_ai_task_queued)
        }
    }

    fun start() {
        AiTaskQueue.enqueue(this)
    }

    override fun execute() {
        if (_status.value == UpscaleStatus.Running) return
        _status.postValue(UpscaleStatus.Running)
        _progress.postValue(0f)

        val illustPage = parseIllustPageKey(taskKey)
        if (illustPage != null) {
            val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
            state.upscaleStatus = UpscaleStatus.Running
            state.upscaleProgress = 0f
            state.upscaleEta = 0f
            state.upscaleStatusText = context.getString(ceui.lisa.R.string.string_ai_upscale_running, model.displayName)
        }

        scope.launch {
            try {
                val result = NcnnUpscaler.upscale(context, inputFile, model) { percent, etaSeconds ->
                    _progress.postValue(percent)
                    _eta.postValue(etaSeconds)
                    if (illustPage != null) {
                        val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
                        state.upscaleProgress = percent
                        state.upscaleEta = etaSeconds
                    }
                }
                if (result != null) {
                    if (illustPage != null) {
                        val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
                        state.upscaleStatus = UpscaleStatus.Done
                        state.upscaleResultPath = result.absolutePath
                        state.upscaleOriginalPath = originalFilePath
                    }
                    _resultFile.postValue(result)
                    _status.postValue(UpscaleStatus.Done)
                } else {
                    if (illustPage != null) {
                        val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
                        state.upscaleStatus = UpscaleStatus.Failed
                    }
                    _status.postValue(UpscaleStatus.Failed)
                }
            } catch (e: Exception) {
                if (illustPage != null) {
                    val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
                    state.upscaleStatus = UpscaleStatus.Failed
                }
                _status.postValue(UpscaleStatus.Failed)
            } finally {
                AiTaskQueue.onTaskFinished(this@UpscaleTask)
            }
        }
    }

    companion object {
        fun illustKey(illustId: Long, pageIndex: Int = 0): String = "illust_${illustId}_$pageIndex"
        fun galleryKey(): String = "gallery_${System.currentTimeMillis()}"
    }
}

object UpscaleTaskPool {

    private val _tasks = ConcurrentHashMap<String, UpscaleTask>()

    fun getTask(key: String): UpscaleTask? = _tasks[key]

    fun findTasksForIllust(illustId: Long): List<Pair<Int, UpscaleTask>> {
        val prefix = "illust_${illustId}_"
        val legacyPrefix = "illust_${illustId}"
        return _tasks.entries.mapNotNull { (key, task) ->
            if (key.startsWith(prefix)) {
                val page = key.substringAfterLast('_').toIntOrNull() ?: 0
                page to task
            } else if (key == legacyPrefix) {
                0 to task
            } else {
                null
            }
        }
    }

    fun startTask(
        key: String,
        context: Context,
        inputFile: File,
        originalPath: String,
        model: UpscaleModel = UpscaleModel.REAL_ESRGAN
    ): UpscaleTask {
        val existing = _tasks[key]
        if (existing != null && (existing.status.value == UpscaleStatus.Running || existing.status.value == UpscaleStatus.Queued)) {
            return existing
        }

        val task = UpscaleTask(key, context.applicationContext, inputFile, originalPath, model)
        _tasks[key] = task
        task.start()
        return task
    }

    fun removeTask(key: String) {
        _tasks.remove(key)
        AiTaskQueue.remove(key)
    }
}

class RembgTask(
    override val taskKey: String,
    private val context: Context,
    private val inputFile: File,
    val originalFilePath: String,
    val model: RembgModel = RembgModel.U2NETP
) : AiQueueItem {
    private val _progress = MutableLiveData(0f)
    val progress: LiveData<Float> = _progress
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableLiveData(UpscaleStatus.Idle)
    override val status: LiveData<UpscaleStatus> = _status

    private val _resultFile = MutableLiveData<File?>()
    val resultFile: LiveData<File?> = _resultFile

    override fun markQueued() {
        if (_status.value == UpscaleStatus.Running || _status.value == UpscaleStatus.Done) return
        _status.postValue(UpscaleStatus.Queued)
        val illustPage = parseIllustPageKey(taskKey)
        if (illustPage != null) {
            val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
            state.rembgStatus = UpscaleStatus.Queued
            state.rembgProgress = 0f
            state.rembgStatusText = context.getString(ceui.lisa.R.string.string_ai_task_queued)
        }
    }

    fun start() {
        AiTaskQueue.enqueue(this)
    }

    override fun execute() {
        if (_status.value == UpscaleStatus.Running) return
        _status.postValue(UpscaleStatus.Running)
        _progress.postValue(0f)

        val illustPage = parseIllustPageKey(taskKey)
        if (illustPage != null) {
            val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
            state.rembgStatus = UpscaleStatus.Running
            state.rembgProgress = 0f
            state.rembgStatusText = context.getString(ceui.lisa.R.string.string_ai_rembg_running)
        }

        scope.launch {
            try {
                val result = BackgroundRemover.removeBackground(context, inputFile, model) { percent ->
                    _progress.postValue(percent)
                    if (illustPage != null) {
                        val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
                        state.rembgProgress = percent
                    }
                }
                if (result != null) {
                    if (illustPage != null) {
                        val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
                        state.rembgStatus = UpscaleStatus.Done
                        state.rembgResultPath = result.absolutePath
                        state.rembgOriginalPath = originalFilePath
                    }
                    _resultFile.postValue(result)
                    _status.postValue(UpscaleStatus.Done)
                } else {
                    if (illustPage != null) {
                        val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
                        state.rembgStatus = UpscaleStatus.Failed
                    }
                    _status.postValue(UpscaleStatus.Failed)
                }
            } catch (e: Exception) {
                if (illustPage != null) {
                    val state = IllustAiStore.getOrCreate(illustPage.first, illustPage.second)
                    state.rembgStatus = UpscaleStatus.Failed
                }
                _status.postValue(UpscaleStatus.Failed)
            } finally {
                AiTaskQueue.onTaskFinished(this@RembgTask)
            }
        }
    }

    companion object {
        fun illustKey(illustId: Long, pageIndex: Int = 0): String = "rembg_${illustId}_$pageIndex"
    }
}

object RembgTaskPool {

    private val _tasks = ConcurrentHashMap<String, RembgTask>()

    fun getTask(key: String): RembgTask? = _tasks[key]

    fun findTasksForIllust(illustId: Long): List<Pair<Int, RembgTask>> {
        val prefix = "rembg_${illustId}_"
        return _tasks.entries.mapNotNull { (key, task) ->
            if (key.startsWith(prefix)) {
                val page = key.substringAfterLast('_').toIntOrNull() ?: 0
                page to task
            } else {
                null
            }
        }
    }

    fun startTask(
        key: String,
        context: Context,
        inputFile: File,
        originalPath: String,
        model: RembgModel = RembgModel.U2NETP
    ): RembgTask {
        val existing = _tasks[key]
        if (existing != null && (existing.status.value == UpscaleStatus.Running || existing.status.value == UpscaleStatus.Queued)) {
            return existing
        }

        val task = RembgTask(key, context.applicationContext, inputFile, originalPath, model)
        _tasks[key] = task
        task.start()
        return task
    }

    fun removeTask(key: String) {
        _tasks.remove(key)
        AiTaskQueue.remove(key)
    }
}
