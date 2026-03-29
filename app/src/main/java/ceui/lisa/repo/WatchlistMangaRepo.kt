package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListWatchlistManga
import io.reactivex.Observable

class WatchlistMangaRepo: RemoteRepo<ListWatchlistManga>() {
    override fun initApi(): Observable<out ListWatchlistManga> {
        return Retro.getAppApi().getWatchlistManga()
    }

    override fun initNextApi(): Observable<out ListWatchlistManga> {
        return Retro.getAppApi().getNextWatchlistManga(nextUrl)
    }
}
