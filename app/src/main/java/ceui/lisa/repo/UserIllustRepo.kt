package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.Params
import io.reactivex.Observable

class UserIllustRepo @JvmOverloads constructor(
    private val userID: Int,
    private val initialOffset: Int = 0
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return if (initialOffset > 0) {
            Retro.getAppApi().getNextIllust(buildOffsetUrl(userID, Params.TYPE_ILLUST, initialOffset))
        } else {
            Retro.getAppApi().getUserSubmitIllust(userID, Params.TYPE_ILLUST)
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(nextUrl)
    }
}

internal fun buildOffsetUrl(userID: Int, type: String, offset: Int): String =
    "https://app-api.pixiv.net/v1/user/illusts" +
        "?filter=for_android&user_id=$userID&type=$type&offset=$offset"
