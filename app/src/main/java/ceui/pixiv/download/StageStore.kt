package ceui.pixiv.download

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.security.MessageDigest

/**
 * 断点续传的持久化后盾 —— 把「我已经下了多少字节」和「怎么安全地接着下」拆成
 * 磁盘上两样东西：
 *
 *   1. `{key}.part`  —— 已下字节本体。它的**文件长度就是续传 offset 的唯一真相**，
 *      不再另建 DB 列去记 offset。
 *   2. `{key}.part.meta` —— 极小的 JSON sidecar（[Manifest]），只存「怎么安全接着下」
 *      需要的东西：源 URL、HTTP validator（ETag / Last-Modified）、总大小。
 *
 * # 为什么 key 用 URL 哈希而不是 DownloadItem.uuid
 *
 * 旧实现把 stage 文件命名成 `{uuid}.part`，而 uuid 是 `DownloadItem` 构造函数里
 * `UUID.randomUUID()` 现场生成的（见 `DownloadItem`）。批量队列每次重试 / 冷启动都
 * `new DownloadItem(...)` → 新 uuid → 旧 `.part` 变成找不回的孤儿，续传形同虚设。
 *
 * URL（`illust` 某页某分辨率的原图地址）在同一资源的所有下载尝试之间**稳定且唯一**：
 *   - 同 (illustId, page) 不同分辨率是不同 URL → 不同 stage，不会串。
 *   - 同一次失败重试、暂停继续、进程被杀后冷启动，算出来的 key 都一样 → 找回同一个
 *     `.part` 接着下。cacheDir 能扛过进程死亡，所以冷启动续传是白拿的。
 *
 * # 安全性：续传前必须校验服务器响应（见 [decideWrite]）
 *
 * 光有 offset 还不够。若不校验就把响应 body 追加到 `.part` 尾部，两种情况会把图写坏：
 *   - 服务器忽略 `Range` 头、回 `200` 全量 → 整段字节被当成「尾巴」接到 partial 后面。
 *   - 资源变了（`If-Range` validator 不匹配）→ 服务器回 `200` 全量，同上。
 * 所以 Manager 必须先看响应状态和 `Content-Range`，由 [decideWrite] 判定 append / 重写 /
 * 已完成 / 放弃，再决定用什么模式打开 `.part`。
 *
 * # 纯 JVM
 *
 * 本类刻意不 import 任何 Android 符号，只碰 [File] 和 Gson —— 这样
 * `StageStoreTest` 能在纯 JVM 单测里把 key 生成、manifest 读写、`Content-Range`
 * 解析、[decideWrite] 决策全部覆盖到，不依赖 Robolectric / 模拟器。
 */
object StageStore {

    private val gson = Gson()

    /** stage 目录名，Manager 用 `File(context.getCacheDir(), STAGE_DIR_NAME)`。 */
    const val STAGE_DIR_NAME = "staging_dl"

    private const val PART_SUFFIX = ".part"
    private const val META_SUFFIX = ".part.meta"

    /**
     * 由源 URL 算出稳定 key = sha256(url) 的十六进制前 40 位。
     *
     * 40 位（160 bit）远超碰撞担心的范围；[Manifest.url] 里还存了完整 URL 做二次
     * 确认（[readManifest] 的调用方可比对），哈希只是文件名。
     */
    @JvmStatic
    fun keyForUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.substring(0, 40)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun partFile(stageDir: File, key: String): File = File(stageDir, key + PART_SUFFIX)

    @JvmStatic
    fun metaFile(stageDir: File, key: String): File = File(stageDir, key + META_SUFFIX)

    // ---------- Manifest ----------

    /**
     * `.part.meta` sidecar 内容。字段刻意最小化 —— 已下字节数不存这里（`.part`
     * 文件长度即真相），只存续传时无法从 `.part` 本身推出来的东西。
     *
     * @param url           源 URL，做哈希碰撞的二次确认 + 排障可读性。
     * @param validator     HTTP validator 原值（ETag 或 Last-Modified）；`null` 表示
     *                      服务器没给，续传只能靠 `Content-Range` offset 校验。
     * @param validatorType [VALIDATOR_ETAG] / [VALIDATOR_LASTMOD] / [VALIDATOR_NONE]。
     * @param total         资源总字节数；`-1` 表示未知（chunked / 没有 Content-Length）。
     */
    data class Manifest(
        @JvmField val url: String,
        @JvmField val validator: String?,
        @JvmField val validatorType: String,
        @JvmField val total: Long,
    )

    const val VALIDATOR_ETAG = "etag"
    const val VALIDATOR_LASTMOD = "lastmod"
    const val VALIDATOR_NONE = "none"

    /**
     * 从响应头挑一个 validator：优先 ETag（强校验），退而求其次 Last-Modified。
     * 两个都没有就 [VALIDATOR_NONE]（续传只靠 offset 校验）。
     */
    @JvmStatic
    fun buildManifest(url: String, etag: String?, lastModified: String?, total: Long): Manifest {
        val cleanEtag = etag?.trim()?.takeIf { it.isNotEmpty() }
        val cleanLm = lastModified?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            cleanEtag != null -> Manifest(url, cleanEtag, VALIDATOR_ETAG, total)
            cleanLm != null -> Manifest(url, cleanLm, VALIDATOR_LASTMOD, total)
            else -> Manifest(url, null, VALIDATOR_NONE, total)
        }
    }

    /** 读 manifest；文件不存在 / 解析失败都返回 `null`（当作没有可续传的元数据）。 */
    @JvmStatic
    fun readManifest(metaFile: File): Manifest? {
        if (!metaFile.isFile) return null
        return try {
            val m = gson.fromJson(metaFile.readText(Charsets.UTF_8), Manifest::class.java)
            // Gson 对缺字段会塞 null；url 是必需的最低限度。
            if (m == null || m.url == null) null else m
        } catch (_: JsonParseException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 写 manifest。失败静默（续传是尽力而为，写不了 meta 顶多退化成整段重下）。 */
    @JvmStatic
    fun writeManifest(metaFile: File, manifest: Manifest) {
        try {
            metaFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            metaFile.writeText(gson.toJson(manifest), Charsets.UTF_8)
        } catch (_: Exception) {
            // ignore
        }
    }

    /** 删除某 key 的 `.part` + `.part.meta`。成功 / 永久失败 / 放弃续传时调。 */
    @JvmStatic
    fun clear(stageDir: File, key: String) {
        runCatching { partFile(stageDir, key).delete() }
        runCatching { metaFile(stageDir, key).delete() }
    }

    // ---------- Content-Range 解析 ----------

    /**
     * `Content-Range` 解析结果。两种形态：
     *   - `bytes 200-1023/1024`（206）→ start/end/total 都在；
     *   - `bytes * /1024`（416 Range Not Satisfiable）→ 没有 start/end，用 `-1` 占位，
     *     只有 total 有意义。
     * `total` 为 `-1` 表示 `*`（未知）。
     */
    data class ContentRange(
        @JvmField val start: Long,
        @JvmField val end: Long,
        @JvmField val total: Long,
    )

    // 兼容 206 的 `start-end` 与 416 的 `*`：range 段整体可为 `*`，或 `\d+-\d+`。
    private val CONTENT_RANGE_RE =
        Regex("""bytes\s+(?:\*|(\d+)-(\d+))/(\d+|\*)""", RegexOption.IGNORE_CASE)

    /** 解析 `Content-Range` 头；不合法返回 `null`。 */
    @JvmStatic
    fun parseContentRange(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val m = CONTENT_RANGE_RE.matchEntire(header.trim()) ?: return null
        val startRaw = m.groupValues[1]
        val endRaw = m.groupValues[2]
        val totalRaw = m.groupValues[3]
        val total = if (totalRaw == "*") -1L else (totalRaw.toLongOrNull() ?: return null)
        if (startRaw.isEmpty()) {
            // `bytes * /total` 形态（416）：没有具体区间。
            return ContentRange(-1L, -1L, total)
        }
        val start = startRaw.toLongOrNull() ?: return null
        val end = endRaw.toLongOrNull() ?: return null
        if (end < start) return null
        return ContentRange(start, end, total)
    }

    // ---------- 写入决策 ----------

    enum class WriteMode {
        /** 从 offset 0 全量写（截断已有 `.part`）。对应 200 或校验不通过的兜底。 */
        FRESH,
        /** 从 [WriteDecision.startOffset] 追加。对应校验通过的 206。 */
        APPEND,
        /** 服务器 416 且我们已持有全部字节 —— 不必再下，直接提交现有 `.part`。 */
        ALREADY_COMPLETE,
        /** 无法安全续传（206 offset 对不上 / 416 但字节数矛盾）—— 弃掉 partial 整段重下。 */
        ABORT,
    }

    /**
     * 单一真相：拿到响应头后，判定该怎么把字节落到 `.part`。
     *
     * @param httpCode       响应状态码。
     * @param contentRange   `Content-Range` 头原值（可空）。
     * @param contentLength  `Content-Length`（本次响应 body 长度，未知传 `-1`）。
     * @param existingLen    当前 `.part` 已有字节数（= 我们请求的 Range 起点）。
     */
    @JvmStatic
    fun decideWrite(
        httpCode: Int,
        contentRange: String?,
        contentLength: Long,
        existingLen: Long,
    ): WriteDecision {
        return when (httpCode) {
            200 -> {
                // 服务器无视 Range（或本就是首次全量）→ 从头写。total = body 长度。
                WriteDecision(WriteMode.FRESH, 0L, if (contentLength >= 0) contentLength else -1L)
            }
            206 -> {
                val cr = parseContentRange(contentRange)
                    // 206 却没有可解析的 Content-Range：不敢盲目 append。
                    ?: return WriteDecision(WriteMode.ABORT, 0L, -1L)
                if (cr.start != existingLen) {
                    // 服务器从别的 offset 开始发 —— 接上去会错位。放弃 partial。
                    WriteDecision(WriteMode.ABORT, 0L, cr.total)
                } else {
                    val total = when {
                        cr.total >= 0 -> cr.total
                        contentLength >= 0 -> existingLen + contentLength
                        else -> -1L
                    }
                    WriteDecision(WriteMode.APPEND, existingLen, total)
                }
            }
            416 -> {
                // Range Not Satisfiable。若已知总长且我们的 partial 恰好等于总长，说明
                // 字节已经齐了，直接提交；否则 partial 与服务器认知矛盾，整段重下。
                val cr = parseContentRange(contentRange)
                val total = cr?.total ?: -1L
                if (total >= 0 && existingLen == total) {
                    WriteDecision(WriteMode.ALREADY_COMPLETE, existingLen, total)
                } else {
                    WriteDecision(WriteMode.ABORT, 0L, total)
                }
            }
            else -> WriteDecision(WriteMode.ABORT, 0L, -1L)
        }
    }

    class WriteDecision(
        @JvmField val mode: WriteMode,
        @JvmField val startOffset: Long,
        @JvmField val total: Long,
    )

    // ---------- 孤儿 GC ----------

    /**
     * 扫 [stageDir]，删掉「最后修改时间早于 [now] - [maxAgeMs]」的 `.part` / `.part.meta`。
     *
     * 正在下载的 `.part` 会被高频 write 顶新 lastModified，只有真正被遗弃的
     * （永久失败 / 用户清空队列 / 崩溃残留）才会老到被扫走。因此纯按年龄回收就安全，
     * 不必把「哪些 key 还活着」这份信息传进来（那需要把队列里每条 illust 解析成 URL，
     * 代价大且易错）。冷启动跑一次即可。
     *
     * @return 删除的文件个数（.part 和 .meta 分别计）。
     */
    @JvmStatic
    fun sweepOrphans(stageDir: File, maxAgeMs: Long, now: Long): Int {
        if (!stageDir.isDirectory) return 0
        val files = stageDir.listFiles() ?: return 0
        var deleted = 0
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            if (!name.endsWith(PART_SUFFIX) && !name.endsWith(META_SUFFIX)) continue
            if (now - f.lastModified() < maxAgeMs) continue
            if (f.delete()) deleted++
        }
        return deleted
    }
}
