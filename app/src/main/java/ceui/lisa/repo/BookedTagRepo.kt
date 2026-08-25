package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListTag

class BookedTagRepo(
    private val type: Int,
    private val starType: String?,
) : RemoteRepo<ListTag>() {

    override suspend fun initApi(): ListTag {
        // starType 为 null 时 Retrofit 省略 restrict query（服务端默认 public），与 legacy 一致。
        if (type == 1) {
            return Retro.getAppApi().getAllNovelBookmarkTags(currentUserID(), starType)
        }
        return Retro.getAppApi().getAllIllustBookmarkTags(currentUserID(), starType)
    }

    override suspend fun initNextApi(): ListTag {
        return Retro.getAppApi().getNextTags(nextUrl)
    }
}
