package ceui.pixiv.feeds.cache

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * 一条首屏快照记录（存储无关的中间表示）：信封版本 + 原始响应 JSON + 下一页游标 + 落盘时间。
 * [FeedFirstPageCache] 只认这个结构，不认底层是 Room 还是别的——换存储 / 单测替身都靠它解耦。
 */
data class FeedCacheRecord(
    val schemaVersion: Int,
    val payloadJson: String,
    val nextCursor: String?,
    val savedAt: Long,
)

/**
 * 首屏快照的底层字节存取端口。只认「键 → 记录」，序列化 / 过期 / 命名空间都在上层
 * （[FeedFirstPageCache]）。抽出接口一是为了可测（单测注入内存假实现，不碰 Android），
 * 二是为了让本模块不必认识任何数据库——落地实现归宿主（本仓是 :app 的 RoomFeedCacheBackend，
 * 写进 AppDatabase 的 `feed_cache_table`）。
 * 实现自行保证 main-safe（重 IO 自己切线程）。
 */
interface FeedCacheBackend {
    suspend fun load(key: String): FeedCacheRecord?
    suspend fun save(key: String, record: FeedCacheRecord)
    suspend fun remove(key: String)
}

/**
 * 首屏落盘用的进程级 scope（fire-and-forget）。落盘是给「下次冷启」用的，与当前刷新的展示、
 * 也与页面 / 请求生命周期都无关——即便用户在写盘途中离开页面，这次新首屏也应落盘成功，
 * 所以不挂 viewModelScope。SupervisorJob：单次写失败不牵连其它。
 *
 * [CoroutineExceptionHandler] 是兜底：write 内部已 catch(Exception)，但这是个游离 scope，
 * 万一序列化抛出非 Exception 的 Throwable（大响应 OOM / 深图 StackOverflowError / 存储层 Error），
 * 没有 handler 会走线程默认处理器直接崩进程。有它则一律吞掉留痕，落盘失败绝不牵连 UI。
 */
val feedCacheWriteScope: CoroutineScope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.IO +
        CoroutineExceptionHandler { _, t -> Timber.w(t, "feed cache 写入协程异常，忽略") }
)
