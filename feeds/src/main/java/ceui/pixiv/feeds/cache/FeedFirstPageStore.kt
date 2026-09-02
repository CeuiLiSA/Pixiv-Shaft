package ceui.pixiv.feeds.cache

/**
 * 「本地优先」首屏快照的存取契约：[ceui.pixiv.feeds.FeedSource.loadFromCache] 从 [read] 恢复，
 * 网络首屏成功后经 [write] 落下。
 *
 * 磁盘实现是 [FeedFirstPageCache]（Room + gson，跨进程存活）；进程内的一级内存层
 * （如评论第一页在详情页预览与评论列表页之间的交接）实现同一接口，数据源不必知道快照住在哪。
 *
 * 契约与 [ceui.pixiv.feeds.FeedSource.loadFromCache] 一致：main-safe、不碰网络、坏数据即未命中、
 * 取消照常向上传播。
 */
interface FeedFirstPageStore<Resp : Any> {

    /** 读回快照；无 / 过期 / 损坏都返回 null。 */
    suspend fun read(): CachedFirstPage<Resp>?

    /** 存下最新首屏（网络首屏成功时调）。失败只留痕不打断加载。 */
    suspend fun write(response: Resp, nextCursor: String?)
}
