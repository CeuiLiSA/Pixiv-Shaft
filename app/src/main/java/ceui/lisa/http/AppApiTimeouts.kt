package ceui.lisa.http

/**
 * App API / OAuth / PxveAPI 代理的超时统一入口（Java / Kotlin 共用）。
 *
 * 只做收敛：连接超时现收敛到 5s，读/写超时仍保持原来的 10s。
 * 所有调用点统一从这里取值，避免散落硬编码。
 *
 * 这些值覆盖以下调用段：
 * - [Retro.buildRetrofit]：AccountTokenApi（oauth.secure.pixiv.net 刷新 token）与 SignApi 共用。
 * - [ceui.pixiv.api.ClientManager.createAPPAPI]：app-api。
 * - [ceui.pixiv.login.PixivLogin.buildClient]：OAuth 登录交换 / refresh_token 刷新。
 * - 以上 client 开启 PxveAPI 代理时，[AppApiProxyInterceptor] 改写后的代理请求
 *   仍走这些 client，因此代理路径同样使用这里的超时。
 */
object AppApiTimeouts {

    /** 连接超时（秒）。现收敛到5s。 */
    const val CONNECT_TIMEOUT_SECONDS = 5L

    /** 读超时（秒）。值 = 原 OkHttp 默认 10s / 旧 REQUIEST_TIME 10s。 */
    const val READ_TIMEOUT_SECONDS = 10L

    /** 写超时（秒）。app-api 相关 client 显式/默认均为 10s，一并收敛。 */
    const val WRITE_TIMEOUT_SECONDS = 10L
}
