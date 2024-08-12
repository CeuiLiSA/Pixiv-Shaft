package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.model.RecmdIllust
import io.reactivex.Observable
/**
 * The class represents for recommended illustrations
 * */
open class RecmdIllustRepo(
    private val dataType: String?
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<RecmdIllust> {
        return if ("漫画" == dataType) {//DOUBT:Why use hardcoded string here
            Retro.getAppApi().getRecmdManga(token())
        } else {
            Retro.getAppApi().getRecmdIllust(token(), true)
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }

    companion object {
        const val RankingIllustTag = "RankingIllustTag"
    }
}
