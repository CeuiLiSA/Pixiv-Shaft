package ceui.pixiv.ui.comic.reader

import ceui.lisa.database.ComicBookmarkEntity
import ceui.lisa.models.IllustsBean
import kotlinx.coroutines.CoroutineScope

/**
 * UseCase 层封装业务规则。Fragment / ViewModel 调用它们，业务规则不再散布在 UI 层。
 *
 * 设计要点：
 * - 每个 UseCase 一个动词 + 一个最小职责（CQS：commands return Unit / 简单 Result）
 * - UseCase 知道 Repository / 协调器，但不知道任何 Android Framework 类型
 * - 输入参数显式（不偷读全局 Settings/SharedPref —— 方便单测）
 */

/** URL 解析的业务规则：根据"是否加载原图"决定具体走 preview 还是 original。 */
object ComicPageUrlResolver {
    fun resolve(page: ComicReaderV3ViewModel.ComicPage, loadOriginal: Boolean): String =
        if (loadOriginal) page.originalUrl else page.previewUrl
}

/**
 * 添加书签的业务规则：
 *   - 必须有有效的 illust + page；
 *   - preview URL 缺失时退回 original；
 *   - 时间戳由 use case 注入（避免调用方各自调 currentTimeMillis 不一致）；
 *   - 写库走 [ComicBookmarkRepository] 的应用级 IO scope，跨 view lifecycle 可靠落库。
 */
class AddComicBookmarkUseCase(private val repo: ComicBookmarkRepository) {

    fun invoke(
        illust: IllustsBean,
        pages: List<ComicReaderV3ViewModel.ComicPage>,
        pageIndex: Int,
        note: String = "",
    ): Result {
        val page = pages.getOrNull(pageIndex) ?: return Result.InvalidPage
        val preview = page.previewUrl.ifEmpty { page.originalUrl }
        repo.add(
            ComicBookmarkEntity(
                illust.id.toLong(),
                pageIndex,
                pages.size,
                preview,
                note,
                System.currentTimeMillis(),
            )
        )
        return Result.Added(pageIndex)
    }

    sealed class Result {
        data class Added(val pageIndex: Int) : Result()
        object InvalidPage : Result()
    }
}

/**
 * 阅读会话落库：唯一一个写时长 / 翻页计数的入口，避免散布的 dao.upsert 调用。
 */
class FlushComicSessionUseCase(private val repo: ComicStatsRepository) {

    fun invoke(
        illustId: Long,
        currentPage: Int,
        totalPages: Int,
        durationMs: Long,
        flips: Int,
    ) {
        if (durationMs <= 0L && flips <= 0) return
        val completed = totalPages > 0 && currentPage >= totalPages - 1
        repo.flushSession(illustId, currentPage, totalPages, durationMs, flips, completed)
    }
}

/**
 * 系列上下篇业务：把"无系列 / 边界 / 找到"三态明确返回。
 * Fragment 仅按 [Outcome] 选 Toast 文案与跳转 Intent，不再持有"如何找邻居"的知识。
 */
class JumpComicSeriesUseCase(private val navigator: ComicSeriesNavigator) {

    fun invoke(
        scope: CoroutineScope,
        illust: IllustsBean,
        forward: Boolean,
        onResult: (Outcome) -> Unit,
    ) {
        val seriesId = illust.series?.id?.toLong() ?: 0L
        navigator.jump(scope, seriesId, illust.id.toLong(), forward) { result ->
            onResult(when (result) {
                ComicSeriesNavigator.Result.NoSeries -> Outcome.NoSeries
                is ComicSeriesNavigator.Result.Boundary ->
                    if (result.forward) Outcome.AtLast else Outcome.AtFirst
                is ComicSeriesNavigator.Result.Found -> Outcome.Found(result.illustId)
            })
        }
    }

    sealed class Outcome {
        data class Found(val illustId: Long) : Outcome()
        object NoSeries : Outcome()
        object AtFirst : Outcome()
        object AtLast : Outcome()
    }
}
