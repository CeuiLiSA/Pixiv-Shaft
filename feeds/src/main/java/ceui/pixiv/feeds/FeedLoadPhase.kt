package ceui.pixiv.feeds

/**
 * 一次页映射所处的加载阶段，传给 `:app` 的 `PixivFeedSource` 的 mapper。
 *
 * 取代原来的 `isFirstPage: Boolean`：多出的 [CacheRestore] 让「磁盘缓存恢复」与
 * 「真网络首屏」在映射上一致（都要建 section 头等），但在副作用上分开——缓存恢复
 * 只做纯映射，不重放「拉取成功」的副作用（喂画像池、写浏览历史……），否则会拿旧数据
 * 污染下游（典型：REPLACE upsert 的浏览历史被旧条目盖上新时间戳）。
 */
enum class FeedLoadPhase {

    /** 网络首屏 / 下拉刷新的第一页。 */
    FirstPage,

    /** 向后翻页。 */
    NextPage,

    /** 磁盘快照恢复首屏：映射同首屏，但不做「拉取成功」的副作用。 */
    CacheRestore;

    /** 映射意义上的首页（首屏或缓存恢复都要建头、走首页分支）。 */
    val isFirstPage: Boolean
        get() = this != NextPage

    /** 是否为真网络拉取（首屏 / 翻页）——false 即缓存恢复，别做拉取成功的副作用。 */
    val isFreshFetch: Boolean
        get() = this != CacheRestore
}
