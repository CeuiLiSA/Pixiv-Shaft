package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListTrendingtag
import io.reactivex.Observable

class HotTagRepo(
    private val contentType: String?
) : RemoteRepo<ListTrendingtag>() {

    override fun initApi(): Observable<ListTrendingtag> {
        return Retro.getAppApi().getHotTags(token(), contentType)
    }

    override fun initNextApi(): Observable<ListTrendingtag>? {
        return null
    }
}
