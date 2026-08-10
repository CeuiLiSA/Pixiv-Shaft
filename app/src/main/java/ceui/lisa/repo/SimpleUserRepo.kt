package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListSimpleUser
import io.reactivex.Observable

class SimpleUserRepo(private val illustID: Int) : RemoteRepo<ListSimpleUser>() {

    override fun initApi(): Observable<ListSimpleUser> {
        return Retro.getAppApi().getUsersWhoLikeThisIllust(illustID)
    }

    override fun initNextApi(): Observable<ListSimpleUser> {
        return Retro.getAppApi().getNextSimpleUser(nextUrl)
    }
}

/**
 * 「喜欢这部小说的用户」——插画版 [SimpleUserRepo] 的小说孪生体。
 *
 * `v1/novel/bookmark/users` 不在 app-api 的公开文档里，但确实存在：无 token 打它回 400
 * （OAuth 报错，说明路由命中），打一个不存在的路径回 404。响应结构与插画版一致
 * （`users` + `next_url`），所以翻页照旧共用 getNextSimpleUser。
 */
class NovelBookmarkUserRepo(private val novelID: Long) : RemoteRepo<ListSimpleUser>() {

    override fun initApi(): Observable<ListSimpleUser> {
        return Retro.getAppApi().getUsersWhoLikeThisNovel(novelID)
    }

    override fun initNextApi(): Observable<ListSimpleUser> {
        return Retro.getAppApi().getNextSimpleUser(nextUrl)
    }
}
