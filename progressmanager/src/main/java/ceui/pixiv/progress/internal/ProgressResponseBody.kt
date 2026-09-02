package ceui.pixiv.progress.internal

import ceui.pixiv.progress.Clock
import ceui.pixiv.progress.DownloadProgress
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

/**
 * 包在真实响应体外面的计数流：每次 `read` 累加字节数，按节流规则把 [DownloadProgress] 交给 [sink]。
 *
 * 只负责「数字节 + 节流」，不认识 URL 也不认识订阅者 —— 谁该收到由 [sink] 背后的
 * [ceui.pixiv.progress.ProgressTracker] 每次现查，所以订阅者中途退出后这里不会再打扰它。
 *
 * 完成判定见 [DownloadProgress.isDone]；完成只报一次，之后消费者再 read 到 -1 也不重复报。
 * `close()` 不报任何东西：被取消的下载不是完成。
 */
internal class ProgressResponseBody(
    private val body: ResponseBody,
    private val refreshIntervalMs: Long,
    private val clock: Clock,
    private val sink: (DownloadProgress) -> Unit,
) : ResponseBody() {

    /** network 层的 `body.source()` 只是取字段、没有 IO，直接建好，不留一个 lazy 状态给线程边缘情况。 */
    private val bufferedSource: BufferedSource = CountingSource(body.source()).buffer()

    override fun contentType(): MediaType? = body.contentType()

    override fun contentLength(): Long = body.contentLength()

    override fun source(): BufferedSource = bufferedSource

    private inner class CountingSource(source: Source) : ForwardingSource(source) {

        /**
         * `contentLength()` 每次都会走一遍原始 body，只取一次。
         * 注意 [ForwardingSource] 自带一个叫 `delegate` 的属性（那是 Source），所以外层的 body 不叫 delegate。
         */
        private val total: Long? = body.contentLength().takeIf { it >= 0L }

        private var bytesRead = 0L
        private var lastEmitMs: Long? = null
        private var doneReported = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (doneReported) return n
            if (n > 0L) bytesRead += n

            val done = n == -1L || (total != null && bytesRead >= total)
            if (done) {
                doneReported = true
                emit(isDone = true)
                return n
            }

            val now = clock.nowMs()
            val last = lastEmitMs
            if (last == null || now - last >= refreshIntervalMs) {
                lastEmitMs = now
                emit(isDone = false)
            }
            return n
        }

        private fun emit(isDone: Boolean) {
            this@ProgressResponseBody.sink(
                DownloadProgress(bytesRead = bytesRead, contentLength = total, isDone = isDone),
            )
        }
    }
}
