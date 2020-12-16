package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListArticle
import ceui.lisa.utils.Dev
import io.reactivex.Observable

open class PivisionRepo(
        private val dataType: String?,
        private val isHorizontal: Boolean
) : RemoteRepo<ListArticle>() {

    override fun initApi(): Observable<ListArticle> {
        return Retro.getAppApi().getArticles(token(), dataType)
    }

    override fun initNextApi(): Observable<ListArticle>? {
        if (isHorizontal) {
            return null
        }
        return Retro.getAppApi().getNextArticals(token(), nextUrl)
    }

    override fun localData(): Boolean {
        return Dev.isDev
    }
}