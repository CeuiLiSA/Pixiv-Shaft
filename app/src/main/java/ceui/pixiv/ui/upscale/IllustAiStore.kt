package ceui.pixiv.ui.upscale

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PageAiState(
    val illustId: Long,
    val pageIndex: Int,
    upscaleStatus: UpscaleStatus = UpscaleStatus.Idle,
    var upscaleProgress: Float = 0f,
    var upscaleEta: Float = 0f,
    var upscaleStatusText: String? = null,
    var upscaleResultPath: String? = null,
    var upscaleOriginalPath: String? = null,

    rembgStatus: UpscaleStatus = UpscaleStatus.Idle,
    var rembgProgress: Float = 0f,
    var rembgStatusText: String? = null,
    var rembgResultPath: String? = null,
    var rembgOriginalPath: String? = null,
) {
    internal var onStatusChanged: (() -> Unit)? = null

    var upscaleStatus = upscaleStatus
        set(value) {
            if (field == value) return
            field = value
            onStatusChanged?.invoke()
        }

    var rembgStatus = rembgStatus
        set(value) {
            if (field == value) return
            field = value
            onStatusChanged?.invoke()
        }

    var upscaleRunning: Boolean
        get() = upscaleStatus == UpscaleStatus.Running || upscaleStatus == UpscaleStatus.Preparing
        set(value) {
            upscaleStatus = if (value) UpscaleStatus.Running else if (upscaleStatus == UpscaleStatus.Running) UpscaleStatus.Idle else upscaleStatus
        }

    var rembgRunning: Boolean
        get() = rembgStatus == UpscaleStatus.Running || rembgStatus == UpscaleStatus.Preparing
        set(value) {
            rembgStatus = if (value) UpscaleStatus.Running else if (rembgStatus == UpscaleStatus.Running) UpscaleStatus.Idle else rembgStatus
        }

    val isRunning: Boolean get() = upscaleStatus == UpscaleStatus.Running || rembgStatus == UpscaleStatus.Running
    val isQueued: Boolean get() = (upscaleStatus == UpscaleStatus.Queued && !isRunning) || (rembgStatus == UpscaleStatus.Queued && !isRunning)
    val isWorking: Boolean get() = isRunning || upscaleStatus == UpscaleStatus.Queued || rembgStatus == UpscaleStatus.Queued ||
        upscaleStatus == UpscaleStatus.Preparing || rembgStatus == UpscaleStatus.Preparing

    val hasUpscaleResult: Boolean
        get() = upscaleResultPath?.let { File(it).exists() } == true

    val hasRembgResult: Boolean
        get() = rembgResultPath?.let { File(it).exists() } == true

    val hasAnyResult: Boolean
        get() = hasUpscaleResult || hasRembgResult

    private val resultKey: String get() = "$upscaleResultPath|$rembgResultPath"
    private var acknowledgedResultKey: String? = null

    /**
     * 大图页的完成遮罩是全屏的，还会挡住拖动退出，只为用户没看过也没关过的结果显示。
     * 新结果路径不同，遮罩会重新出现。
     */
    val hasUnacknowledgedResult: Boolean
        get() = hasAnyResult && acknowledgedResultKey != resultKey

    fun acknowledgeResult() {
        acknowledgedResultKey = resultKey
    }

    internal fun pruneMissingResults() {
        val wasAcknowledged = acknowledgedResultKey == resultKey
        var removed = false
        if (upscaleResultPath?.let { !File(it).exists() } == true) {
            upscaleResultPath = null
            upscaleStatus = UpscaleStatus.Idle
            removed = true
        }
        if (rembgResultPath?.let { !File(it).exists() } == true) {
            rembgResultPath = null
            rembgStatus = UpscaleStatus.Idle
            removed = true
        }
        if (removed) {
            // Generated filenames are deterministic: a later result at the same path is new.
            // Keep the acknowledgement only for an already viewed result that survived cleanup.
            acknowledgedResultKey = if (wasAcknowledged &&
                (upscaleResultPath != null || rembgResultPath != null)) resultKey else null
        }
    }

    val isIndeterminate: Boolean
        get() = if (upscaleStatus == UpscaleStatus.Running) {
            upscaleProgress <= 0f
        } else if (rembgStatus == UpscaleStatus.Running) {
            rembgProgress <= 0f
        } else {
            isWorking
        }

    val currentProgress: Float
        get() = if (upscaleStatus == UpscaleStatus.Running) upscaleProgress else if (rembgStatus == UpscaleStatus.Running) rembgProgress else 0f

    val currentEta: Float
        get() = if (upscaleStatus == UpscaleStatus.Running) upscaleEta else 0f

    fun currentStatusText(context: Context): String {
        return if (isQueued) {
            context.getString(R.string.string_ai_task_queued)
        } else if (upscaleStatus == UpscaleStatus.Running) {
            upscaleStatusText ?: context.getString(R.string.ai_upscaling_in_progress)
        } else if (rembgStatus == UpscaleStatus.Running) {
            rembgStatusText ?: context.getString(R.string.string_ai_rembg_running)
        } else if (isWorking) {
            context.getString(R.string.string_ai_waiting_for_original_image)
        } else {
            ""
        }
    }
}

object IllustAiStore {
    private val states = ConcurrentHashMap<String, PageAiState>()
    private val _changes = MutableLiveData(Unit)
    /** 状态切换用于重新发现入池任务；进度仍由任务自身 LiveData 驱动，不逐帧刷新所有页。 */
    val changes: LiveData<Unit> = _changes

    private fun key(illustId: Long, pageIndex: Int): String = "${illustId}_$pageIndex"

    fun getOrCreate(illustId: Long, pageIndex: Int): PageAiState {
        return states.computeIfAbsent(key(illustId, pageIndex)) {
            PageAiState(illustId, pageIndex).apply {
                onStatusChanged = { _changes.postValue(Unit) }
            }
        }
    }

    fun get(illustId: Long, pageIndex: Int): PageAiState? = states[key(illustId, pageIndex)]

    fun allPages(illustId: Long): List<PageAiState> {
        val prefix = "${illustId}_"
        return states.filterKeys { it.startsWith(prefix) }.values.toList()
    }

    /** Drop stale result paths after storage cleanup so visible artwork pages redraw their AI pills. */
    fun pruneMissingResults() {
        for (state in states.values) {
            state.pruneMissingResults()
        }
        _changes.postValue(Unit)
    }

    fun clear(illustId: Long) {
        val prefix = "${illustId}_"
        states.keys.filter { it.startsWith(prefix) }.forEach { states.remove(it) }
    }
}
