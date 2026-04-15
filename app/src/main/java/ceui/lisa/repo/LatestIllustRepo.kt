package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.pixiv.db.discovery.DiscoveryPool
import io.reactivex.Observable
import timber.log.Timber

class LatestIllustRepo(
    private val workType: String?
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNewWorks(workType)
            .doOnNext { listIllust ->
                Timber.d("Discovery/Repo latest type=$workType, got ${listIllust?.illusts?.size} items")
                DiscoveryPool.collect(listIllust?.illusts, "latest:$workType")
            }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(nextUrl)
            .doOnNext { listIllust ->
                Timber.d("Discovery/Repo latest_next type=$workType, got ${listIllust?.illusts?.size} items")
                DiscoveryPool.collect(listIllust?.illusts, "latest_next:$workType")
            }
    }
}
