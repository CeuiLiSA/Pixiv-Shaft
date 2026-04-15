package ceui.lisa.repo

import ceui.lisa.activities.Shaft
import ceui.lisa.core.Mapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.PixivOperate
import io.reactivex.Observable
import io.reactivex.functions.Function

class RankIllustRepo(
    private val mode: String?,//The type of rank,such as day/week/.../
    private val date: String?
) : RemoteRepo<ListIllust>() {

    /**
     * @return BodyObservable
     */
    override fun initApi(): Observable<ListIllust> {
        return Retro.getAppApi().getRank(mode, date)
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(nextUrl)
    }

    override fun mapper(): Function<in ListIllust, ListIllust> {
        return Function { listIllust ->
            val mapped = Mapper<ListIllust>().apply(listIllust)
            if (Shaft.sSettings.isFilterRankBookmarked) {
                val filtered = PixivOperate.getListWithoutBooked(mapped)
                mapped.setIllusts(filtered)
            }
            mapped
        }
    }
}
