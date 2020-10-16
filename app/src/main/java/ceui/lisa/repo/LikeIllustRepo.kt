package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import io.reactivex.Observable

class LikeIllustRepo(
        private val userID: Int,
        private val starType: String?,
        var tag: String?
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return if (TextUtils.isEmpty(tag)) {
            Retro.getAppApi().getUserLikeIllust(token(), userID, starType)
        } else {
            Retro.getAppApi().getUserLikeIllust(token(), userID, starType, tag)
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(token(), nextUrl)
    }
}