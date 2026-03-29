package ceui.pixiv.ui.upscale

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.pixiv.ui.task.TaskPool
import kotlinx.coroutines.launch
import java.io.File

enum class UpscaleStatus { Idle, Running, Done, Failed }

class UpscaleTask(
    val illustId: Int,
    private val context: Context,
    private val inputFile: File,
    private val originalPath: String
) {
    private val _progress = MutableLiveData(0f)
    val progress: LiveData<Float> = _progress

    private val _eta = MutableLiveData(0f)
    val eta: LiveData<Float> = _eta

    private val _status = MutableLiveData(UpscaleStatus.Idle)
    val status: LiveData<UpscaleStatus> = _status

    private val _resultFile = MutableLiveData<File?>()
    val resultFile: LiveData<File?> = _resultFile

    val originalFilePath: String get() = originalPath

    fun start() {
        if (_status.value == UpscaleStatus.Running) return
        _status.value = UpscaleStatus.Running
        _progress.value = 0f

        TaskPool.scope.launch {
            val result = RealESRGANUpscaler.upscale(context, inputFile) { percent, etaSeconds ->
                _progress.postValue(percent)
                _eta.postValue(etaSeconds)
            }
            if (result != null) {
                _resultFile.postValue(result)
                _status.postValue(UpscaleStatus.Done)
            } else {
                _status.postValue(UpscaleStatus.Failed)
            }
        }
    }
}

object UpscaleTaskPool {

    private val _tasks = mutableMapOf<Int, UpscaleTask>()

    fun getTask(illustId: Int): UpscaleTask? = _tasks[illustId]

    fun startTask(illustId: Int, context: Context, inputFile: File, originalPath: String): UpscaleTask {
        val existing = _tasks[illustId]
        if (existing != null && existing.status.value == UpscaleStatus.Running) return existing

        val task = UpscaleTask(illustId, context.applicationContext, inputFile, originalPath)
        _tasks[illustId] = task
        task.start()
        return task
    }

    fun removeTask(illustId: Int) {
        _tasks.remove(illustId)
    }
}
