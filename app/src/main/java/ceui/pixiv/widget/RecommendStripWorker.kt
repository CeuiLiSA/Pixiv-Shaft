package ceui.pixiv.widget

import android.content.Context
import androidx.work.WorkerParameters
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecommendStripWorker(
    context: Context,
    params: WorkerParameters,
) : BaseStripWidgetWorker(context, params) {

    override val providerClass = RecommendStripWidgetProvider::class.java

    override suspend fun fetchIllusts(): List<IllustsBean>? = withContext(Dispatchers.IO) {
        Retro.getAppApiSuspend().getRecmdIllust(true)
            .illusts
            ?.filter { !it.isR18File && !it.isSensitive }
            ?.shuffled()
            ?.take(3)
    }
}
