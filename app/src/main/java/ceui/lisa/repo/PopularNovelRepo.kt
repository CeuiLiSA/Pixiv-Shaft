package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import io.reactivex.Observable

class PopularNovelRepo(private val word: String) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        return Retro.getAppApi().popularNovelPreview(token(), word)
    }

    override fun initNextApi(): Observable<ListNovel> {
        return Retro.getAppApi().getNextNovel(token(), nextUrl)
    }
}
