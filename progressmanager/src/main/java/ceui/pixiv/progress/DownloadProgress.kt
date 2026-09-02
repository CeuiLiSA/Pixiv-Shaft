package ceui.pixiv.progress

/**
 * 一次下载在某个时刻的快照。
 *
 * @param bytesRead     到这一刻为止已经从响应体里读出的字节数。
 * @param contentLength 响应头宣告的总长度；服务端没说（chunked 传输）时为 `null`。
 *                      是 `null` 而不是 `-1`：让「总长未知」在类型上就逼调用方处理，
 *                      而不是算出一个 -0% 静默显示出来。
 * @param isDone        响应体已经读完。要么读到了 EOF，要么已读字节数追平了 [contentLength] ——
 *                      后者也算完成，是因为 Glide 这类消费者按 Content-Length 读够就停，
 *                      不会再多读一次去撞 EOF。
 */
public data class DownloadProgress(
    public val bytesRead: Long,
    public val contentLength: Long?,
    public val isDone: Boolean,
) {

    /** 0..100 的整数百分比；总长未知或为 0 时为 `null`。 */
    public val percent: Int?
        get() {
            val total = contentLength ?: return null
            if (total <= 0L) return null
            return (bytesRead * 100L / total).toInt().coerceIn(0, 100)
        }
}
