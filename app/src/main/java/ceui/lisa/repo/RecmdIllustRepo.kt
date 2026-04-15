package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.model.RecmdIllust
import ceui.pixiv.db.discovery.DiscoveryPool
import io.reactivex.Observable
import timber.log.Timber
/**
 * The class represents for recommended illustrations
 * */
open class RecmdIllustRepo(
    private val dataType: String?
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<RecmdIllust> {
        val source = if ("漫画" == dataType) {
            Retro.getAppApi().getRecmdManga()
        } else {
            Retro.getAppApi().getRecmdIllust(true)
        }
        return source.doOnNext { recmd ->
            // 寄生收集：推荐页数据也进发现池
            Timber.d("Discovery/Repo recmd type=$dataType, got ${recmd?.illusts?.size} items")
            DiscoveryPool.collect(recmd?.illusts, "recmd:$dataType")
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(nextUrl)
            .doOnNext { listIllust ->
                Timber.d("Discovery/Repo recmd_next type=$dataType, got ${listIllust?.illusts?.size} items")
                DiscoveryPool.collect(listIllust?.illusts, "recmd_next:$dataType")
            }
    }

    companion object {
        const val RankingIllustTag = "RankingIllustTag"
    }
}
