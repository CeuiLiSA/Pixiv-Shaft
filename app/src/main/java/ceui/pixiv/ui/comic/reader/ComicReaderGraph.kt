package ceui.pixiv.ui.comic.reader

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase

/**
 * Composition Root（poor man's DI）。
 *
 * 项目还没引 Hilt / Koin，但完全没必要把构造关系散在 Fragment 里。
 * 所有 reader 的依赖（Repository / UseCase / 协调器）从这里拿，Fragment 只保留一个引用。
 *
 * 单例懒初始化：所有 deps 都是无状态或长生存周期对象，跨 Fragment 复用安全。
 */
object ComicReaderGraph {

    private val db by lazy { AppDatabase.getAppDatabase(Shaft.getContext()) }

    val bookmarkRepository: ComicBookmarkRepository by lazy {
        ComicBookmarkRepository(db.comicBookmarkDao())
    }

    val statsRepository: ComicStatsRepository by lazy {
        ComicStatsRepository(db.comicReadingStatsDao())
    }

    val seriesNavigator: ComicSeriesNavigator by lazy { ComicSeriesNavigator() }

    val addBookmarkUseCase: AddComicBookmarkUseCase by lazy {
        AddComicBookmarkUseCase(bookmarkRepository)
    }

    val flushSessionStatsUseCase: FlushComicSessionUseCase by lazy {
        FlushComicSessionUseCase(statsRepository)
    }

    val jumpSeriesUseCase: JumpComicSeriesUseCase by lazy {
        JumpComicSeriesUseCase(seriesNavigator)
    }

    /** 资源 URL 解析的业务规则（preview / original）从 View 层抽离。 */
    val pageUrlResolver: ComicPageUrlResolver = ComicPageUrlResolver
}
