package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import io.reactivex.Observable

class LikeNovelRepo(
    private val userID: Int,
    private val starType: String?,
    var tag: String?,
) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        return if (TextUtils.isEmpty(tag)) {
            Retro.getAppApi().getUserLikeNovel(userID, starType)
        } else {
            Retro.getAppApi().getUserLikeNovel(userID, starType, tag)
        }
    }

    override fun initNextApi(): Observable<ListNovel> {
        return Retro.getAppApi().getNextNovel(nextUrl)
    }
}
