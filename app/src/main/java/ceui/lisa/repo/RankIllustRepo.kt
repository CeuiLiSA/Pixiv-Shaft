package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import io.reactivex.Observable

class RankIllustRepo(
    private val mode: String?,//The type of rank,such as day/week/.../
    private val date: String?
) : RemoteRepo<ListIllust>() {

    /**
     * @return BodyObservable
     */
    override fun initApi(): Observable<ListIllust> {
        //for debug usage
        //var debug = Retro.getAppApi().getRank(token(), mode, date)
        return Retro.getAppApi().getRank(token(), mode, date)
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }
}
