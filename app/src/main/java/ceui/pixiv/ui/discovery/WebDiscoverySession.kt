package ceui.pixiv.ui.discovery

import ceui.pixiv.session.SessionManager
import com.tencent.mmkv.MMKV

/** 网页的收藏状态必须属于执行收藏操作的 App 账号。只约束官网发现，不改变其他网页功能。 */
internal object WebDiscoverySession {
    private val loggedInSession = Regex("""(\d+)_.+""")

    val isCurrentAccount: Boolean
        get() = matchesAccount(
            MMKV.defaultMMKV().getString(SessionManager.COOKIE_KEY, ""),
            SessionManager.loggedInUid,
        )

    fun matchesAccount(cookie: String?, appUid: Long): Boolean {
        if (appUid <= 0L) return false
        // 与 WebHeaderInterceptor 发出的 cookie 完全一致，避免重复 PHPSESSID 的判断分歧。
        val session = SessionManager.normalizeWebCookie(cookie).split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("PHPSESSID=") }
            ?.substringAfter('=') ?: return false
        val webUid = loggedInSession.matchEntire(session)?.groupValues?.get(1)?.toLongOrNull()
        return webUid == appUid
    }
}
