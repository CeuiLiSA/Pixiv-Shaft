package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListTag
import ceui.lisa.utils.Params

class BookedTagRepo(
    private val type: Int,
    private val starType: String?,
) : RemoteRepo<ListTag>() {

    override suspend fun initApi(): ListTag {
        // legacy 传 null 时 Retrofit 省略 restrict，服务端按 public 处理；这里显式等价。
        val restrict = starType ?: Params.TYPE_PUBLIC
        if (type == 1) {
            return Retro.getAppApiSuspend().getAllNovelBookmarkTags(currentUserID(), restrict)
        }
        return Retro.getAppApiSuspend().getAllIllustBookmarkTags(currentUserID(), restrict)
    }

    override suspend fun initNextApi(): ListTag {
        return Retro.getAppApiSuspend().getNextTags(nextUrl)
    }
}
