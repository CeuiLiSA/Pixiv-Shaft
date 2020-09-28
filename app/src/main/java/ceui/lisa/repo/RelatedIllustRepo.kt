package ceui.lisa.repo

import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import io.reactivex.Observable

class RelatedIllustRepo(private val illustID: Int) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return Retro.getAppApi().relatedIllust(token(), illustID)
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), getNextUrl())
    }

    override fun hasNext(): Boolean {
        return Shaft.sSettings.isRelatedIllustNoLimit
    }
}