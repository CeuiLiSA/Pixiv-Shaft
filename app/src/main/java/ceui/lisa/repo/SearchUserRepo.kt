package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListUser
import ceui.lisa.viewmodel.SearchModel
import io.reactivex.Observable

class SearchUserRepo(private var word: String?) : RemoteRepo<ListUser>() {

    override fun initApi(): Observable<ListUser> {
        return Retro.getAppApi().searchUser(token(), word)
    }

    override fun initNextApi(): Observable<ListUser> {
        return Retro.getAppApi().getNextUser(token(), nextUrl)
    }

    fun update(keyWord: String){
        word=keyWord;
    }
}
