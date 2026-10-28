package ceui.pixiv.ui.upscale

import android.content.Context
import ceui.lisa.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PageAiState(
    val illustId: Long,
    val pageIndex: Int,
    var upscaleStatus: UpscaleStatus = UpscaleStatus.Idle,
    var upscaleProgress: Float = 0f,
    var upscaleEta: Float = 0f,
    var upscaleStatusText: String? = null,
    var upscaleResultPath: String? = null,
    var upscaleOriginalPath: String? = null,

    var rembgStatus: UpscaleStatus = UpscaleStatus.Idle,
    var rembgProgress: Float = 0f,
    var rembgStatusText: String? = null,
    var rembgResultPath: String? = null,
    var rembgOriginalPath: String? = null,
) {
    var upscaleRunning: Boolean
        get() = upscaleStatus == UpscaleStatus.Running
        set(value) {
            upscaleStatus = if (value) UpscaleStatus.Running else if (upscaleStatus == UpscaleStatus.Running) UpscaleStatus.Idle else upscaleStatus
        }

    var rembgRunning: Boolean
        get() = rembgStatus == UpscaleStatus.Running
        set(value) {
            rembgStatus = if (value) UpscaleStatus.Running else if (rembgStatus == UpscaleStatus.Running) UpscaleStatus.Idle else rembgStatus
        }

    val isRunning: Boolean get() = upscaleStatus == UpscaleStatus.Running || rembgStatus == UpscaleStatus.Running
    val isQueued: Boolean get() = (upscaleStatus == UpscaleStatus.Queued && !isRunning) || (rembgStatus == UpscaleStatus.Queued && !isRunning)
    val isWorking: Boolean get() = isRunning || upscaleStatus == UpscaleStatus.Queued || rembgStatus == UpscaleStatus.Queued

    val hasUpscaleResult: Boolean
        get() = upscaleResultPath?.let { File(it).exists() } == true

    val hasRembgResult: Boolean
        get() = rembgResultPath?.let { File(it).exists() } == true

    val hasAnyResult: Boolean
        get() = hasUpscaleResult || hasRembgResult

    val isIndeterminate: Boolean
        get() = if (isQueued) {
            true
        } else if (rembgRunning) {
            rembgProgress <= 0f
        } else if (upscaleRunning) {
            upscaleProgress <= 0f
        } else {
            false
        }

    val currentProgress: Float
        get() = if (upscaleRunning) upscaleProgress else if (rembgRunning) rembgProgress else 0f

    val currentEta: Float
        get() = if (upscaleRunning) upscaleEta else 0f

    fun currentStatusText(context: Context): String {
        return if (isQueued) {
            context.getString(R.string.string_ai_task_queued)
        } else if (upscaleRunning) {
            upscaleStatusText ?: context.getString(R.string.ai_upscaling_in_progress)
        } else if (rembgRunning) {
            rembgStatusText ?: context.getString(R.string.string_ai_rembg_running)
        } else {
            ""
        }
    }
}

object IllustAiStore {
    private val states = ConcurrentHashMap<String, PageAiState>()

    private fun key(illustId: Long, pageIndex: Int): String = "${illustId}_$pageIndex"

    fun getOrCreate(illustId: Long, pageIndex: Int): PageAiState {
        return states.computeIfAbsent(key(illustId, pageIndex)) {
            PageAiState(illustId, pageIndex)
        }
    }

    fun get(illustId: Long, pageIndex: Int): PageAiState? = states[key(illustId, pageIndex)]

    fun allPages(illustId: Long): List<PageAiState> {
        val prefix = "${illustId}_"
        return states.filterKeys { it.startsWith(prefix) }.values.toList()
    }

    fun clear(illustId: Long) {
        val prefix = "${illustId}_"
        states.keys.filter { it.startsWith(prefix) }.forEach { states.remove(it) }
    }
}
