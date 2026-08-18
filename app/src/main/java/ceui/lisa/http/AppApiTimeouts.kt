package ceui.lisa.http

/**
 * App API / OAuth / PxveAPI 代理的超时统一入口（Java / Kotlin 共用）。
 *
 * 只做收敛，不改值：当前各调用点要么显式 10s，要么走 OkHttp 默认 10s，
 * 因此这里统一取 10s，行为与之前完全一致。
 *
 * 这些值覆盖以下调用段：
 * - [Retro.getLogClient]：老栈 AppApi（app-api.pixiv.net）与 AccountTokenApi
 *   （oauth.secure.pixiv.net 刷新 token）共用同一个 OkHttp builder；
 *   该 builder 也用于 web/sign/resource，值为原 OkHttp 默认 10s，未变。
 * - [ceui.loxia.ClientManager.createAPPAPI]：新栈 app-api。
 * - [ceui.pixiv.login.PixivLogin.buildClient]：OAuth 登录交换 / refresh_token 刷新。
 * - 以上 client 开启 PxveAPI 代理时，[AppApiProxyInterceptor] 改写后的代理请求
 *   仍走这些 client，因此代理路径同样使用这里的超时。
 */
object AppApiTimeouts {

    /** 连接超时（秒）。值 = 原 OkHttp 默认 10s / loxia REQUIEST_TIME 10s。 */
    const val CONNECT_TIMEOUT_SECONDS = 10L

    /** 读超时（秒）。值 = 原 OkHttp 默认 10s / loxia REQUIEST_TIME 10s。 */
    const val READ_TIMEOUT_SECONDS = 10L

    /** 写超时（秒）。app-api 相关 client 显式/默认均为 10s，一并收敛。 */
    const val WRITE_TIMEOUT_SECONDS = 10L
}
