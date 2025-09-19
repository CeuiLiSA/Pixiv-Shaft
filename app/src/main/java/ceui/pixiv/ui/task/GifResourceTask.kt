package ceui.pixiv.ui.task

import ceui.lisa.models.GifResponse
import kotlinx.coroutines.CoroutineScope

class GifResourceTask(private val coroutineScope: CoroutineScope) : QueuedRunnable<GifResponse>() {

    override suspend fun execute() {

    }
}