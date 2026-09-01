package ceui.pixiv.ui.comments

import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Stamp
import ceui.pixiv.api.model.StampsResponse
import ceui.pixiv.ui.common.createResponseStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 评论「表情贴图」目录:GET /v1/stamps 返回官方常驻的 40 个贴纸,小体量且不随人变化,
 * 进程内缓存一次即可,不必每次打开面板都重新请求。
 *
 * 请求失败不外抛——内部吞掉异常,退回磁盘持久化的上一次成功响应([ResponseStore],同
 * TrendingTagsDataSource 等已在用的收口);网络成功只缓存非空目录，失败时若磁盘也为空则
 * 保持未加载状态，切回贴图页仍可重试。并发首次读取由 [loadMutex] 串行化，避免重复写缓存。
 */
object StampCatalog {
    @Volatile
    private var cache: List<Stamp>? = null
    private val loadMutex = Mutex()
    private val store = createResponseStore<StampsResponse>({ "stamps-catalog" })

    suspend fun get(): List<Stamp> {
        cache?.let { return it }
        return loadMutex.withLock {
            cache?.let { return@withLock it }
            try {
                val response = Client.appApi.getStamps()
                if (response.stamps.isNotEmpty()) {
                    withContext(Dispatchers.IO) { store.writeToCache(response) }
                    cache = response.stamps
                }
                response.stamps
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "StampCatalog: fetch failed, falling back to disk cache")
                withContext(Dispatchers.IO) { store.loadFromCache()?.stamps.orEmpty() }
                    .also { cached -> if (cached.isNotEmpty()) cache = cached }
            }
        }
    }
}
