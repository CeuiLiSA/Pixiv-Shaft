package ceui.lisa.repo

import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListSimpleUser

class SimpleUserRepo(private val illustID: Int) : RemoteRepo<ListSimpleUser>() {

    override suspend fun initApi(): ListSimpleUser {
        return Retro.getAppApiSuspend().getUsersWhoLikeThisIllust(illustID)
    }

    override suspend fun initNextApi(): ListSimpleUser {
        return Retro.getAppApiSuspend().getNextSimpleUser(nextUrl)
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

    override suspend fun initApi(): ListSimpleUser {
        return Retro.getAppApiSuspend().getUsersWhoLikeThisNovel(novelID)
    }

    override suspend fun initNextApi(): ListSimpleUser {
        return Retro.getAppApiSuspend().getNextSimpleUser(nextUrl)
    }
}
