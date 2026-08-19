package ceui.lisa.http

/**
 * www.pixiv.net 网页 ajax 的超时统一入口（Java / Kotlin 共用）。
 *
 * 与 AppApiTimeouts 分离：网页 ajax 和 app-api 是两条独立链路，
 * 后续可以各自调整而不互相影响。当前连接收敛到 5s，读/写保持 10s。
 */
object WebApiTimeouts {

    /** 连接超时（秒）。当前收敛到 5s。 */
    const val CONNECT_TIMEOUT_SECONDS = 5L

    /** 读超时（秒）。保持原 10s。 */
    const val READ_TIMEOUT_SECONDS = 10L

    /** 写超时（秒）。保持原 10s。 */
    const val WRITE_TIMEOUT_SECONDS = 10L
}
