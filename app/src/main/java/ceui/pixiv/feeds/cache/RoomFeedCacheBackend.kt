package ceui.pixiv.feeds.cache

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.FeedCacheDao
import ceui.pixiv.db.FeedCacheEntity
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * [FeedCacheBackend]（住在 :feeds 模块，只认「键 → 记录」）在本 app 的落地实现：写进
 * AppDatabase 的 `feed_cache_table`，每次写后做一次 LRU 淘汰。
 * 所有 DAO 调用切到 [Dispatchers.IO]（DAO 本身是阻塞式）。
 *
 * 留在 :app 而不是随框架下沉：表和 38→39 迁移都长在 [AppDatabase] 上，:feeds 不认识 Room，
 * 也不该为了一个可选的本地优先能力把整个 Room 依赖拖进去。
 */
internal class RoomFeedCacheBackend(private val dao: FeedCacheDao) : FeedCacheBackend {

    override suspend fun load(key: String): FeedCacheRecord? = withContext(Dispatchers.IO) {
        dao.find(key)?.let {
            FeedCacheRecord(it.schemaVersion, it.payloadJson, it.nextCursor, it.savedAt)
        }
    }

    override suspend fun save(key: String, record: FeedCacheRecord) = withContext(Dispatchers.IO) {
        dao.upsert(
            FeedCacheEntity(
                cacheKey = key,
                schemaVersion = record.schemaVersion,
                payloadJson = record.payloadJson,
                nextCursor = record.nextCursor,
                savedAt = record.savedAt,
            )
        )
        dao.trimToNewest(MAX_CACHED_SLOTS)
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { dao.delete(key) }
    }

    private companion object {
        /** 保活的快照槽位上限（几个 feed × 几个账号，足量且有界）。 */
        private const val MAX_CACHED_SLOTS = 24
    }
}

/** 进程级默认存储：本仓单一 Room 库的 feedCacheDao。内部使用，惰性建。 */
internal val defaultFeedCacheBackend: FeedCacheBackend by lazy {
    RoomFeedCacheBackend(AppDatabase.getAppDatabase(Shaft.getContext()).feedCacheDao())
}

/**
 * 生产用工厂：绑定进程级 Room 存储 + 当前账号命名空间 + [Shaft.sGson]（与全仓模型序列化一致）。
 *
 * @param slot 该 feed 的稳定标识（如 `"recmd-illust"`）；账号命名空间由本工厂自动拼上。
 * @param maxAge 超过多旧就不再闪缓存（默认 [DEFAULT_FEED_CACHE_MAX_AGE]）。
 */
fun <Resp : Any> feedFirstPageCache(
    slot: String,
    type: Class<Resp>,
    maxAge: Duration = DEFAULT_FEED_CACHE_MAX_AGE,
): FeedFirstPageCache<Resp> = FeedFirstPageCache(
    slot = slot,
    type = type,
    maxAgeMillis = maxAge.inWholeMilliseconds,
    backend = defaultFeedCacheBackend,
    gson = Shaft.sGson,
    accountId = { SessionManager.loggedInUid },
)
