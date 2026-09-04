package ceui.pixiv.widget

import android.content.Context
import androidx.work.WorkerParameters
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DailyRankStripWorker(
    context: Context,
    params: WorkerParameters,
) : BaseStripWidgetWorker(context, params) {

    override val providerClass = DailyRankStripWidgetProvider::class.java

    override suspend fun fetchIllusts(): List<Illust>? = withContext(Dispatchers.IO) {
        Client.appApi.getRank("day", null)
            .illusts
            ?.filter { !it.isR18File() && !it.isSensitive() }
            ?.take(3)
    }
}
