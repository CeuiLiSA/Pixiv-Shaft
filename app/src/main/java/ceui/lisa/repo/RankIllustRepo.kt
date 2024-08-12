package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import io.reactivex.Observable
import ceui.lisa.http.LofterApi.*
import ceui.lisa.http.Retro.*
import ceui.lisa.utils.Common
import okhttp3.Response

class RankIllustRepo(
    private val mode: String?,//The type of rank,such as day/week/.../
    private val date: String?
) : RemoteRepo<ListIllust>() {

    /**
     * @return BodyObservable
     */
    override fun initApi(): Observable<ListIllust> {
        //for debug usage
        //var debug = Retro.getLofterApi().getLofterRank(LOFTER_HEADER, LOFTER_APICOOKIE)
        return Retro.getAppApi().getRank(token(), mode, date)
    }
//
//    override fun initLofterApi(): Observable<ListIllust> {
//        //for debug usage
//        var debug_value = Retro.getLofterApi().getLofterRank(LOFTER_HEADER, LOFTER_APICOOKIE)
//        Common.showLog("initLofterApi")
//        Common.showLog(debug_value)
//        return Retro.getLofterApi().getLofterRank(LOFTER_HEADER, LOFTER_APICOOKIE)
//    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }
}
