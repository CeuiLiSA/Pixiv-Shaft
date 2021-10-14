package ceui.lisa.repo

import ceui.lisa.core.CommentFilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListComment
import ceui.lisa.utils.Params
import io.reactivex.Observable
import io.reactivex.functions.Function

class CommentRepo(
    private val workId: Int,
    private val dataType: String
) : RemoteRepo<ListComment>() {

    override fun initApi(): Observable<ListComment> {
        return when (dataType) {
            Params.TYPE_ILLUST -> {
                Retro.getAppApi().getIllustComment(token(), workId)
            }
            else -> {
                Retro.getAppApi().getNovelComment(token(), workId)
            }
        }
    }

    override fun initNextApi(): Observable<ListComment> {
        return Retro.getAppApi().getNextComment(token(), nextUrl)
    }

    override fun mapper(): Function<in ListComment, ListComment> {
        return CommentFilterMapper()
    }
}
