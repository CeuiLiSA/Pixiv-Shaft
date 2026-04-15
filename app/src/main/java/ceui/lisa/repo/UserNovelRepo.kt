package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import io.reactivex.Observable

class UserNovelRepo @JvmOverloads constructor(
    private val userID: Int,
    private val initialOffset: Int = 0
) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        return if (initialOffset > 0) {
            Retro.getAppApi().getNextNovel(
                "https://app-api.pixiv.net/v1/user/novels?user_id=$userID&offset=$initialOffset"
            )
        } else {
            Retro.getAppApi().getUserSubmitNovel(userID)
        }
    }

    override fun initNextApi(): Observable<ListNovel> {
        return Retro.getAppApi().getNextNovel(nextUrl)
    }
}
