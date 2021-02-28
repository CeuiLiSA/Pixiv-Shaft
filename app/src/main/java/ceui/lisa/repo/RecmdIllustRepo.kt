package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.model.RecmdIllust
import io.reactivex.Observable

open class RecmdIllustRepo(
    private val dataType: String?
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<RecmdIllust> {
        return if ("漫画" == dataType) {
            Retro.getAppApi().getRecmdManga(token())
        } else {
            Retro.getAppApi().getRecmdIllust(token())
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }
}
