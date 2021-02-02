package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListComment
import io.reactivex.Observable

class CommentRepo(private val illustID: Int) : RemoteRepo<ListComment>() {

    override fun initApi(): Observable<ListComment> {
        return Retro.getAppApi().getComment(token(), illustID)
    }

    override fun initNextApi(): Observable<ListComment> {
        return Retro.getAppApi().getNextComment(token(), nextUrl)
    }
}
