package ceui.pixiv.feeds.cache

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** 「本地优先」快照默认最多显示多旧的数据：超过就不再闪缓存，退回常规首屏 loading。 */
val DEFAULT_FEED_CACHE_MAX_AGE: Duration = 7.days

/** 一份从磁盘恢复出来的首屏：原始响应 + 下一页游标 + 落盘时间。 */
data class CachedFirstPage<out Resp>(
    val payload: Resp,
    val nextCursor: String?,
    val savedAtMillis: Long,
)

/**
 * 一个具名首屏缓存槽位（已绑定 slot + 类型）。一个可缓存的 feed 一个。
 *
 * 职责：账号命名空间键、信封版本校验、过期判定、序列化、坏数据即未命中、全程离主线程、
 * 取消可传播。存储引擎由注入的 [backend] 决定（生产走 Room，单测走内存假实现）。
 *
 * 零捕获：只持有 [backend]（进程单例）、[accountId] / [now]（读全局，惰性求值），
 * 不碰 Fragment / View / Context，可安全被 [ceui.pixiv.feeds.FeedViewModel] 长期持有。
 */
class FeedFirstPageCache<Resp : Any>(
    private val slot: String,
    private val type: Class<Resp>,
    private val maxAgeMillis: Long,
    private val backend: FeedCacheBackend,
    private val gson: Gson,
    private val accountId: () -> Long,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * slot 拼当前账号 + 载荷类型全名：
     * - 不同账号互不覆盖；
     * - 不同 [Resp] 类型（两个 feed 撞同一 slot、或某 slot 被改用于新类型）落到不同键，
     *   杜绝把 A 类型的 JSON 用 gson 宽松反序列化进 B 类型的静默串味——各按各自类型解析，
     *   旧类型残行随 LRU 淘汰。（应用换混淆映射后类型名变化 → 一次冷启 miss，无害。）
     */
    private val key: String
        get() = "$slot#${accountId()}#${type.name}"

    /**
     * 读回快照；无 / 版本不符 / 过期 / 损坏都返回 null。取消照常向上传播。
     * 计时打点：本地优先的冷启体验全靠这一步够快（IO 派发 + gson 反序列化），耗时异常时
     * 用 key 定位是哪个 feed / 哪个账号的槽位偏慢。
     */
    suspend fun read(): CachedFirstPage<Resp>? {
        val startNanos = System.nanoTime()
        val result = readInternal()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        Timber.d(
            "feed cache 读取 key=%s 耗时 %dms %s",
            key, elapsedMs, if (result != null) "命中" else "未命中",
        )
        return result
    }

    private suspend fun readInternal(): CachedFirstPage<Resp>? {
        val record = try {
            backend.load(key)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            Timber.w(ex, "feed cache 读取失败 key=%s", key)
            return null
        } ?: return null

        if (record.schemaVersion != SCHEMA_VERSION) return null
        if (now() - record.savedAt > maxAgeMillis) return null

        return try {
            val payload = withContext(Dispatchers.Default) {
                gson.fromJson(record.payloadJson, type)
            } ?: return null
            CachedFirstPage(payload, record.nextCursor, record.savedAt)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            Timber.w(ex, "feed cache 反序列化失败 key=%s", key)
            null
        }
    }

    /** 落盘最新首屏（网络首屏成功时调）。失败只留痕不打断加载。 */
    suspend fun write(response: Resp, nextCursor: String?) {
        val json = try {
            withContext(Dispatchers.Default) { gson.toJson(response) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            Timber.w(ex, "feed cache 序列化失败 key=%s", key)
            return
        }
        // 首屏快照本应只有几十~几百 KB；异常大的响应（脏数据 / 未来接口膨胀）跳过落盘，
        // 别让单行 TEXT 撑大共享库、拖慢写入。冷启拿不到缓存自动退回网络，无副作用。
        if (json.length > MAX_PAYLOAD_CHARS) {
            Timber.w("feed cache 快照过大(%d 字符)，跳过落盘 key=%s", json.length, key)
            return
        }
        try {
            backend.save(key, FeedCacheRecord(SCHEMA_VERSION, json, nextCursor, now()))
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            Timber.w(ex, "feed cache 写入失败 key=%s", key)
        }
    }

    suspend fun clear() {
        try {
            backend.remove(key)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Exception) {
            Timber.w(ex, "feed cache 清除失败 key=%s", key)
        }
    }

    companion object {
        /** 信封版本：快照结构（存的字段 / 序列化约定）变了就 +1，旧快照自动作废，无需迁移。 */
        const val SCHEMA_VERSION = 1

        /** 单条快照 JSON 上限（字符）：约 1M 字符 ≈ 数 MB，远超正常首屏(几十~几百 KB)，只挡病态大响应。 */
        private const val MAX_PAYLOAD_CHARS = 1_000_000
    }
}
