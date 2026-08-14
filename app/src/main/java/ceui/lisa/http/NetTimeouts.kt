package ceui.lisa.http

/**
 * 全项目 OkHttp 超时策略统一入口（Java / Kotlin 共用）。
 *
 * 原则：
 * - **connectTimeout 一律 3s**：连不上 / 被墙 / 代理失效要在 3s 内失败，让 UI 弹「去网络测试」
 *   而不是干等 10~30s。TCP+TLS 握手在正常网络上远快于 3s，没有合法场景需要更长的连接超时。
 * - **readTimeout / writeTimeout 默认 3s**：适用于小 JSON 请求 / 响应。
 * - **大体积传输显式放宽**：图片显示、文件下载、整包正文抓取、批量上传、流式翻译、WebSocket
 *   各自有例外常量；放宽的是读 / 写超时，连接阶段仍然 3s，断网场景由 connectTimeout 兜住。
 *
 * 例外清单（对应各调用点，改动时先看这里再动值）：
 * - [IMAGE_READ_SECONDS]：Glide 显示图（含原图预览 / 漫画阅读器默认原图），见 Shaft.onCreate。
 * - [DOWNLOAD_READ_SECONDS]：Manager 原图下载，见 Manager.getDownloadOkHttpClient。
 * - [UGOIRA_READ_SECONDS]：ugoira zip 下载，见 UgoiraEngine.ugoiraHttpClient。
 * - 模型下载：ModelDownloadManager 子类各自 readTimeoutSeconds（60~180s）。
 * - AI 翻译流式响应：AiTranslator 用户可配 readTimeout（30~600s）。
 * - [BODY_READ_SECONDS]：整包正文下载（CSRF 首页 HTML、moon 云端设置包）。
 * - [UPLOAD_WRITE_SECONDS]：大 JSON 上传（历史批量上报、moon 设置包）。
 * - WebSocket 长连接：RobustWebSocketClient 显式 readTimeout(0)。
 * - 网络测试页（NetworkTestViewModel）：诊断工具自己的测量窗口（5~20s），刻意不跟随钳制。
 * - Cronet 整体上限：[CronetInterceptor] 默认取 [REQUEST_TIMEOUT_SECONDS]（3s），需要放宽的
 *   调用点显式传值——CSRF 整页抓取传 [BODY_READ_SECONDS]，网络测试页取各自 client 的 readTimeout。
 */
object NetTimeouts {
    /** 所有 OkHttp 客户端的连接超时（秒）。 */
    const val CONNECT_SECONDS = 3L

    /** 小请求 / 小响应 API 的读超时（秒）。 */
    const val API_READ_SECONDS = 3L

    /** 小请求 / 小响应 API 的写超时（秒）。 */
    const val API_WRITE_SECONDS = 3L

    /** Cronet 直连通道整体请求上限（秒）默认值：OkHttp 的分阶段超时对 Cronet 路径不生效，用它兜底。
     * 小 JSON API 一律 3s；需要放宽的调用点显式传自己的值（CSRF 抓取传 BODY_READ_SECONDS，
     * 网络测试页传各自 readTimeout）。 */
    const val REQUEST_TIMEOUT_SECONDS = 3L

    /** Glide 显示图（含原图）读超时（秒）：慢 CDN 大图包间停顿可能超 3s，放宽读、连接仍 3s。 */
    const val IMAGE_READ_SECONDS = 10L

    /** Manager 原图下载读超时（秒）。 */
    const val DOWNLOAD_READ_SECONDS = 10L

    /** ugoira zip 下载读超时（秒）。 */
    const val UGOIRA_READ_SECONDS = 60L

    /** 整包正文下载读超时（秒）：首页 HTML、云端设置包等可达数百 KB~MB 级。 */
    const val BODY_READ_SECONDS = 10L

    /** 大 JSON 上传写超时（秒）：历史批量上报、云端设置包，慢上行需要时间。 */
    const val UPLOAD_WRITE_SECONDS = 10L
}
