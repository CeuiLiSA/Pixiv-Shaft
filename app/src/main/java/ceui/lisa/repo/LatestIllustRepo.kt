package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.pixiv.db.discovery.DiscoveryPool
import timber.log.Timber

class LatestIllustRepo(
    private val workType: String,
    private val discoveryPool: DiscoveryPool,
) : RemoteRepo<ListIllust>() {

    override suspend fun initApi(): ListIllust {
        val listIllust = Retro.getAppApiSuspend().getNewWorks(workType)
        Timber.d("Discovery/Repo latest type=$workType, got ${listIllust.illusts?.size} items")
        // 过滤前整页喂发现页画像采集（对齐旧 doOnNext：在 mapper 之前）。
        discoveryPool.collect(listIllust.illusts, "latest:$workType")
        return listIllust
    }

    override suspend fun initNextApi(): ListIllust {
        val listIllust = Retro.getAppApiSuspend().getNextIllust(nextUrl)
        Timber.d("Discovery/Repo latest_next type=$workType, got ${listIllust.illusts?.size} items")
        discoveryPool.collect(listIllust.illusts, "latest_next:$workType")
        return listIllust
    }
}
