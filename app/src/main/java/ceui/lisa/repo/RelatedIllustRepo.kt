package ceui.lisa.repo

import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.pixiv.db.discovery.DiscoveryPool
import io.reactivex.Observable
import timber.log.Timber

class RelatedIllustRepo(private val illustID: Int) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return Retro.getAppApi().relatedIllust(illustID)
            .doOnNext { listIllust ->
                // 寄生收集：用户看相关作品时，顺手存入发现池
                Timber.d("Discovery/Repo related illust=$illustID, got ${listIllust?.illusts?.size} items")
                DiscoveryPool.collect(listIllust?.illusts, "related:$illustID")
            }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(getNextUrl())
            .doOnNext { listIllust ->
                Timber.d("Discovery/Repo related_next illust=$illustID, got ${listIllust?.illusts?.size} items")
                DiscoveryPool.collect(listIllust?.illusts, "related_next:$illustID")
            }
    }

    override fun hasNext(): Boolean {
        return Shaft.sSettings.isRelatedIllustNoLimit
    }
}
