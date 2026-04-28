package ceui.pixiv.ui.comic.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 系列上下篇调度器：把"取邻居 → 触发跳转 / Toast 边界"两步业务抽离 Fragment。
 * 用 Result 密封类做明确的成功 / 边界 / 无系列 三态。
 */
class ComicSeriesNavigator {

    sealed class Result {
        data class Found(val illustId: Long) : Result()
        data class Boundary(val forward: Boolean) : Result()
        object NoSeries : Result()
    }

    fun jump(
        scope: CoroutineScope,
        seriesId: Long,
        currentIllustId: Long,
        forward: Boolean,
        onResult: (Result) -> Unit,
    ) {
        if (seriesId == 0L) { onResult(Result.NoSeries); return }
        scope.launch {
            val neighbor = withContext(Dispatchers.IO) {
                ComicSeriesNeighborFinder.findNeighbor(seriesId, currentIllustId, forward)
            }
            onResult(
                if (neighbor == null || neighbor == 0L) Result.Boundary(forward)
                else Result.Found(neighbor)
            )
        }
    }
}
