package ceui.pixiv.plaza.ui

import ceui.pixiv.api.Client
import ceui.pixiv.plaza.*
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.HttpException

/** Tokyo Bearer API. Revisions survive a stopped screen and force fresh signed image URLs. */
internal object PlazaRepository {
    val revision = MutableStateFlow(0L)
    val api get() = Client.plazaAPI
    fun changed() { revision.value += 1 }
    fun requireAccount(uid: Long) {
        check(uid > 0 && uid == SessionManager.loggedInUid) { "账号已变化，请重新打开广场" }
    }
}
internal fun plazaError(error: Exception): String = when (error) {
    is HttpException -> when (error.code()) {
        401 -> "广场认证未完成，请重试"
        404 -> "帖子已删除或不存在"
        409 -> "发布结果待确认，请重试或刷新广场"
        429 -> "操作太频繁，请稍后重试"
        413 -> "图片或内容超过限制"
        else -> "请求失败（${error.code()}），请重试"
    }
    is java.io.IOException -> "网络连接失败，请重试"
    else -> error.message ?: "操作失败，请重试"
}
