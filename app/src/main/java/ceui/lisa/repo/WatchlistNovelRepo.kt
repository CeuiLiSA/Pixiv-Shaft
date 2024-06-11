package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListWatchlistNovel
import io.reactivex.Observable

class WatchlistNovelRepo: RemoteRepo<ListWatchlistNovel>() {
    override fun initApi(): Observable<out ListWatchlistNovel> {
        return Retro.getAppApi().getWatchlistNovel(token())
    }

    override fun initNextApi(): Observable<out ListWatchlistNovel> {
        return Retro.getAppApi().getNextWatchlistNovel(token(), nextUrl)
    }
}