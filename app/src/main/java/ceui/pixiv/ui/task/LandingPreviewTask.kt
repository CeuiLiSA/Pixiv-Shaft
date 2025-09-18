package ceui.pixiv.ui.task

import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.pixiv.utils.GSON_DEFAULT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class LandingPreviewTask(private val coroutineScope: CoroutineScope, private val illustId: Long) :
    QueuedRunnable<Illust>() {

    override suspend fun execute() {
        if (_status.value is TaskStatus.Executing || _status.value is TaskStatus.Finished) {
            onIgnore()
            return
        }

        try {
            onStart()
            _status.value = TaskStatus.Executing(0)
            val illust = Client.appApi.getIllust(illustId).illust
            if (illust != null) {
                Timber.d("saddasads22 ${GSON_DEFAULT.toJson(illust)}")
                _status.value = TaskStatus.Finished
                onEnd(illust)
            } else {
                onError(RuntimeException("null illust found"))
            }
        } catch (ex: Exception) {
            onError(ex)
        }
    }

    override fun start(onNext: () -> Unit) {
        super.start(onNext)

        coroutineScope.launch {
            delay(400L)
            execute()
        }
    }
}