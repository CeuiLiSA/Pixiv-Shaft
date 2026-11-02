package ceui.pixiv.feeds.cache

import ceui.lisa.activities.Shaft
import ceui.pixiv.session.SessionManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.time.Duration

/**
 * 首屏快照的文件存储 —— 取代原先写进 AppDatabase 的 `feed_cache_table`。
 *
 * ## 为什么不再放 SQLite
 *
 * 首页首屏快照是「一个 key 对应一整块 JSON」的 KV，不是关系数据。放进共享的 AppDatabase
 * 意味着它每次读写都要排队拿 SQLite 连接，而全 App 只有一个库实例、连接池上限 4
 * （WAL 下写占 primary、**读只剩 3 个 non-primary**），且 `acquireConnection` 没有超时。
 * 一旦有长查询（无索引的全表排序、`SUM(LENGTH(illustGson))`）把读连接占住，首页的缓存读取
 * 会**无限等待** —— 首页是门面，不能和下载表抢同一条队伍。
 *
 * 换成文件后：首页缓存完全不占 SQLite 连接，也不受库内任何事务 / 锁影响。缓存本身可重建
 * （丢了就重新拉网络），所以不需要事务、不需要迁移、不需要跨表一致性。
 *
 * ## 落盘格式
 *
 * ```
 * {"v":1,"cursor":"...","savedAt":1793...}   ← 第 1 行：信封
 * 原始 payloadJson，从第 2 行起原样存放
 * ```
 *
 * payload 单独占第 2 行起、而不是塞进信封，是为了避免整块 JSON 被再转义一遍
 * （体积 +30%、解析多一层）。读的时候按**第一个**换行切分即可。
 *
 * ## 一致性
 *
 * - **原子替换**：先写 `.tmp` 再 `renameTo`，读到的永远是完整文件（不会读到写了一半的快照）
 * - **写串行化**：[writeMutex]。LRU 淘汰要扫目录删文件，不能和并发的 save 交错
 * - **乱序丢弃**：`save` 是 fire-and-forget（见 `PixivFeedSource`），连续两次刷新时先发的
 *   慢写可能晚到。落盘前比对盘上已有的 `savedAt`，更旧的直接丢掉
 * - 读不加锁：`renameTo` 在同一目录内是原子的，读到的要么是旧文件要么是新文件
 */
internal class FileFeedCacheBackend(
    private val root: File,
) : FeedCacheBackend {

    private val gson = Gson()

    private val writeMutex = Mutex()

    override suspend fun load(key: String): FeedCacheRecord? = withContext(Dispatchers.IO) {
        val f = fileOf(key)
        if (!f.isFile) return@withContext null
        try {
            readRecord(f)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            // 坏文件（写一半、磁盘错误、外部篡改）当成未命中，顺手删掉别让它一直挡路。
            Timber.w(ex, "feed cache 文件损坏，已删除 key=%s", key)
            runCatching { f.delete() }
            null
        }
    }

    override suspend fun save(key: String, record: FeedCacheRecord) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val target = fileOf(key)
            val existing = runCatching { readHeader(target)?.savedAt }.getOrNull()
            if (existing != null && record.savedAt < existing) {
                Timber.d(
                    "feed cache 丢弃较旧的快照 key=%s (%d < %d)",
                    key, record.savedAt, existing,
                )
                return@withLock
            }

            root.mkdirs()
            val tmp = File(root, target.name + TMP_SUFFIX)
            tmp.writeText(encode(record))
            // 同目录 rename 是原子的；目标已存在时个别文件系统会失败，删掉再试一次。
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    Timber.w("feed cache 落盘失败 key=%s", key)
                    tmp.delete()
                    return@withLock
                }
            }
            trimToNewestLocked()
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        fileOf(key).delete()
        Unit
    }

    /**
     * 按账号删：文件名就是 key（`slot#uid#类型全名`），所以 `#uid#` 一次匹配就够。
     *
     * 这里不需要读文件内容 —— 这正是「文件名直接用 key」而不是 hash 的原因：
     * 换成 hash 就得逐个打开文件读信封才能判断属于哪个账号。
     */
    override suspend fun removeByAccount(uid: Long) = withContext(Dispatchers.IO) {
        val marker = "#$uid#"
        var removed = 0
        root.listFiles()?.forEach { f ->
            if (f.isFile && marker in f.name) {
                if (f.delete()) removed++
            }
        }
        if (removed > 0) Timber.d("feed cache 按账号清除 uid=%d，删除 %d 份", uid, removed)
        Unit
    }

    private fun encode(record: FeedCacheRecord): String {
        val header = JsonObject().apply {
            addProperty("v", record.schemaVersion)
            addProperty("cursor", record.nextCursor)
            addProperty("savedAt", record.savedAt)
        }
        return gson.toJson(header) + "\n" + record.payloadJson
    }

    private fun readRecord(f: File): FeedCacheRecord? {
        val text = f.readText()
        val nl = text.indexOf('\n')
        if (nl <= 0) return null
        val header = parseHeader(text.take(nl)) ?: return null
        // payload 原样取回：不 trim、不做任何处理，它必须和写进去时逐字节一致。
        return FeedCacheRecord(header.v, text.substring(nl + 1), header.cursor, header.savedAt)
    }

    /** 只读第一行。用 bufferedReader 而不是 readText —— 后者会把整个 payload 读进内存。 */
    private fun readHeader(f: File): Header? {
        if (!f.isFile) return null
        return f.bufferedReader().use { reader ->
            reader.readLine()?.let(::parseHeader)
        }
    }

    private fun parseHeader(line: String): Header? {
        val json = gson.fromJson(line, JsonObject::class.java) ?: return null
        return Header(
            v = json.get("v").asInt,
            cursor = json.get("cursor")?.takeUnless { it.isJsonNull }?.asString,
            savedAt = json.get("savedAt").asLong,
        )
    }

    /** 只留最新的 [MAX_CACHED_SLOTS] 份。调用方必须持有 [writeMutex]。 */
    private fun trimToNewestLocked() {
        val files = root.listFiles()?.filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) } ?: return
        if (files.size <= MAX_CACHED_SLOTS) return
        val doomed = files
            .map { it to (runCatching { readHeader(it)?.savedAt }.getOrNull() ?: 0L) }
            .sortedByDescending { it.second }
            .drop(MAX_CACHED_SLOTS)
        doomed.forEach { runCatching { it.first.delete() } }
        if (doomed.isNotEmpty()) Timber.d("feed cache LRU 淘汰 %d 份", doomed.size)
    }

    /**
     * key → 文件名。
     *
     * key 的形状由 [FeedFirstPageCache] 决定：`slot#uid#载荷类型全名`，只含字母数字和
     * `- _ . #`，本身就是合法文件名。这里仍然过一遍白名单，挡住将来 slot 改名带来的意外
     * 字符（`/` 会直接变成子目录）。
     *
     * 不做 hash：hash 会让 [removeByAccount] 必须逐个打开文件读信封。
     */
    private fun fileOf(key: String): File {
        val name = buildString(key.length) {
            for (c in key) {
                append(
                    when {
                        c.isLetterOrDigit() -> c
                        c == '-' || c == '_' || c == '.' || c == '#' -> c
                        else -> '_'
                    }
                )
            }
        }
        return File(root, name)
    }

    /**
     * 信封。字段名保持单字符 / 短名，1 份快照的元数据只有几十字节。
     *
     * 读写都手动走 [JsonObject]，不让 Gson 反射本类：release 下 R8 会重命名字段，落盘的 key
     * 就成了当前构建的混淆名，升级后下一个版本的映射不同，整批快照读出来全是错的。
     */
    private data class Header(
        val v: Int,
        val cursor: String?,
        val savedAt: Long,
    )

    private companion object {
        /** 落盘上限。与原先 `feed_cache_table` 的 `MAX_CACHED_SLOTS` 保持一致。 */
        const val MAX_CACHED_SLOTS = 24

        const val TMP_SUFFIX = ".tmp"
    }
}

/**
 * 进程级默认存储。
 *
 * 放 `noBackupFilesDir` 而不是 `cacheDir`：首页首屏快照是门面数据，不该在系统低存储时被
 * 清掉（清了冷启动就闪白）；也不该进 Auto Backup —— 几十 MB 的响应体备份到云端没有意义，
 * 换机后重新拉一次即可。
 */
internal val defaultFeedCacheBackend: FeedCacheBackend by lazy {
    FileFeedCacheBackend(File(Shaft.getContext().noBackupFilesDir, "feed_cache"))
}

/**
 * 生产用工厂：绑定进程级文件存储 + 当前账号命名空间 + [Shaft.sGson]（与全仓模型序列化一致）。
 *
 * @param slot 该 feed 的稳定标识（如 `"recmd-illust"`）；账号命名空间由本工厂自动拼上。
 * @param maxAge 超过多旧就不再闪缓存（默认 [DEFAULT_FEED_CACHE_MAX_AGE]）。
 * @param accountId 默认读当前账号；跨挂起点的请求可绑定发起账号，避免落盘时串号。
 */
fun <Resp : Any> feedFirstPageCache(
    slot: String,
    type: Class<Resp>,
    maxAge: Duration = DEFAULT_FEED_CACHE_MAX_AGE,
    accountId: () -> Long = { SessionManager.loggedInUid },
): FeedFirstPageCache<Resp> = FeedFirstPageCache(
    slot = slot,
    type = type,
    maxAgeMillis = maxAge.inWholeMilliseconds,
    backend = defaultFeedCacheBackend,
    gson = Shaft.sGson,
    accountId = accountId,
)
