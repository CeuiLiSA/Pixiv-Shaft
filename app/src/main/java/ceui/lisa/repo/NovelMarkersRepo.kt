package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovelMarkers
import io.reactivex.Observable

class NovelMarkersRepo: RemoteRepo<ListNovelMarkers>() {
    override fun initApi(): Observable<out ListNovelMarkers> {
        return Retro.getAppApi().getNovelMarkers(token())
    }

    override fun initNextApi(): Observable<out ListNovelMarkers> {
        return Retro.getAppApi().getNextNovelMarkers(token(), nextUrl)
    }
}