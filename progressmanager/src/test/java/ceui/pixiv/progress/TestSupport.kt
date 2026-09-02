package ceui.pixiv.progress

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/** 手动拨的时钟。默认停在 0，测试自己 [advance]。 */
internal class FakeClock(initialMs: Long = 0L) : Clock {

    @Volatile
    var nowMs: Long = initialMs

    override fun nowMs(): Long = nowMs

    fun advance(byMs: Long) {
        nowMs += byMs
    }
}

/**
 * 每次 `read` 最多吐 [chunkSize] 字节的 Source，读完返回 -1。
 * 配合 [chunkedBody] 让「一次 read 读到多少」完全可控，节流和完成判定才能逐次断言。
 */
internal class ChunkedSource(
    private val data: ByteArray,
    private val chunkSize: Int,
    private val failAfterBytes: Long = Long.MAX_VALUE,
    private val failure: () -> Throwable = { java.io.IOException("simulated read failure") },
) : Source {

    private var position = 0
    var reads: Int = 0
        private set
    var closed: Boolean = false
        private set

    override fun read(sink: Buffer, byteCount: Long): Long {
        reads++
        if (position >= failAfterBytes) throw failure()
        if (position >= data.size) return -1L
        val n = min(min(chunkSize.toLong(), byteCount), (data.size - position).toLong()).toInt()
        sink.write(data, position, n)
        position += n
        return n.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}

/**
 * 用 [ChunkedSource] 撑起来的 ResponseBody。[declaredLength] 与真实数据长度可以不一致，
 * 用来模拟服务端 Content-Length 报错的情形；传 -1 模拟 chunked 传输。
 */
internal fun chunkedBody(
    data: ByteArray,
    chunkSize: Int,
    declaredLength: Long = data.size.toLong(),
    source: ChunkedSource = ChunkedSource(data, chunkSize),
    mediaType: MediaType? = null,
): ResponseBody = object : ResponseBody() {
    private val buffered: BufferedSource = source.buffer()
    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = declaredLength
    override fun source(): BufferedSource = buffered
}

/** 线程安全地把收到的每个事件按顺序记下来。 */
internal class RecordingListener : ProgressListener {

    val events: MutableList<DownloadProgress> = CopyOnWriteArrayList()

    override fun onProgress(progress: DownloadProgress) {
        events += progress
    }

    val doneEvents: List<DownloadProgress>
        get() = events.filter { it.isDone }
}

internal fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

/** 把外层 BufferedSource 一次一次地读空，返回每次 read 的返回值序列（含最后的 -1）。 */
internal fun BufferedSource.drainByReads(maxPerRead: Long = 8192L): List<Long> {
    val sink = Buffer()
    val results = ArrayList<Long>()
    while (true) {
        val n = read(sink, maxPerRead)
        results += n
        if (n == -1L) return results
    }
}
