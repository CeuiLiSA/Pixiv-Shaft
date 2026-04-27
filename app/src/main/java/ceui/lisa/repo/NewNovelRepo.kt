package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import ceui.lisa.utils.Params
import io.reactivex.Observable

class NewNovelRepo @JvmOverloads constructor(
    var restrict: String = Params.TYPE_ALL,
) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        return Retro.getAppApi().getBookedUserSubmitNovel(restrict)
    }

    override fun initNextApi(): Observable<ListNovel> {
        return Retro.getAppApi().getNextNovel(nextUrl)
    }
}
