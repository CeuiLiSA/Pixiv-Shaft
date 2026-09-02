package ceui.pixiv.progress

import ceui.pixiv.progress.internal.ProgressResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * 按 URL 订阅 OkHttp 下载进度。
 *
 * 把 [interceptor] 挂到 OkHttpClient 上（或直接 [install]），之后任何对已 [track] 的 URL
 * 发出的请求，其响应体都会被包一层计数流，边读边把 [DownloadProgress] 推给订阅者。
 *
 * ```
 * val tracker = ProgressTracker()
 * val client = tracker.install(OkHttpClient.Builder()).build()
 *
 * tracker.track(url) { p -> progressBar.value = p.percent ?: 0 }.use {
 *     client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.bytes() }
 * }
 * ```
 *
 * ## 为什么是普通 class 而不是单例
 *
 * 前身 `me.jessyan.progressmanager.ProgressManager` 是 `getInstance()` 单例，时钟和节流间隔
 * 都换不掉，单测跑不了。这里所有依赖走构造函数；App 侧持有一个实例的职责交给调用方
 * （见 app 的 `Shaft.getDownloadProgress()`）。
 *
 * ## 键是「发起请求时的 URL」，重定向不丢
 *
 * 匹配用的是 `chain.call().request().url`，即调用方交给 OkHttp 的那条原始 URL，而不是
 * 当前这一跳的 URL。network interceptor 每一跳都会经过一次，用当前跳的 URL 做键的话，
 * 302 之后落地的那一跳就对不上号 —— 前身为此维护了一套把监听器抄到 `Location` 上的逻辑，
 * 这里不需要。3xx 那一跳本身（以及任何非 2xx）不会被包装：给一个 302 的空 body 报一次
 * 「完成」，进度条会先跳满再从头走。
 *
 * ## 线程模型
 *
 * [track] / [untrack] 可从任意线程调用；回调在读响应体的线程上触发（见 [ProgressListener]）。
 * 订阅表由一把锁保护，每次回调前重新查表并拍快照：所以 [ProgressSubscription.close] 之后
 * **立即**收不到回调，哪怕那条流还在读 —— 这是生命周期正确性的前提，取消了的页面不该再被
 * 半路的进度戳到。前身在响应到达那一刻就把监听器数组固化进 body，之后怎么 remove 都没用。
 *
 * @param refreshIntervalMs 两次回调之间的最小间隔（毫秒）。首次 read 和完成那一次总会回调，
 *                          不受节流影响。0 表示每次 read 都回调。
 * @param clock             节流用的时间源，测试传假的。
 * @param onListenerError   订阅者的回调抛 [Exception] 时的出口（[Error] 不经过这里，原样穿出去）。
 *                          回调跑在下载线程上，异常如果原样抛出去会经 okio 一路穿到 OkHttp / Glide，
 *                          把一次好端端的下载判成失败；所以这里接住。默认原样重抛（fail-loud，
 *                          别让 bug 静默）；生产环境传一个打日志的实现。
 */
public class ProgressTracker @JvmOverloads constructor(
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    private val clock: Clock = Clock.MONOTONIC,
    private val onListenerError: (Exception) -> Unit = { throw it },
) {

    init {
        require(refreshIntervalMs >= 0L) { "refreshIntervalMs must be >= 0, was $refreshIntervalMs" }
    }

    private val lock = Any()

    /** key → 订阅者。只在 [lock] 下读写；值列表也只在锁下改，对外一律给快照。 */
    private val listeners: MutableMap<String, MutableList<ProgressListener>> = HashMap()

    /**
     * 要挂成 **network** interceptor（`addNetworkInterceptor`），不是 application interceptor：
     * 只有 network 层拿到的才是真正从 socket 里读的那个 body。application 层看到的 body 可能
     * 来自缓存，或者已经被别的拦截器读过一遍。[install] 已经替你选对了。
     */
    public val interceptor: Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.body
        if (body == null || !response.isSuccessful) return@Interceptor response
        val key = chain.call().request().url.toString()
        if (!isTracked(key)) return@Interceptor response
        response.newBuilder()
            .body(
                ProgressResponseBody(
                    body = body,
                    refreshIntervalMs = refreshIntervalMs,
                    clock = clock,
                    sink = { progress -> dispatch(key, progress) },
                ),
            )
            .build()
    }

    /** 把 [interceptor] 挂到 [builder] 上，返回同一个 builder 方便链式调用。 */
    public fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.addNetworkInterceptor(interceptor)

    /**
     * 订阅 [url] 的下载进度。同一个 URL 可以有多个订阅者；同一个 listener 对同一个 URL 重复
     * 订阅会收到重复回调，由调用方避免。
     *
     * 传进来的 URL 会先按 `HttpUrl` 规范化（host 小写、去掉默认端口等），和拦截器里从
     * `Request.url` 拿到的字符串按同一规则比较；解析不了的字符串原样当键。
     *
     * **要在发请求之前订阅。** 是否包装在响应头到达那一刻决定，已经在流的响应不会被追溯包装；
     * 之后再订阅只会等到下一次对同一 URL 的请求。
     *
     * @return 用完 [ProgressSubscription.close] 掉。**必须**在 `finally` 里关：本类用的是强引用表，
     *         不关就是泄漏 —— 前身用 `WeakHashMap` 以 String 弱键「自动回收」，代价是订阅刚注册、
     *         响应头还没回来的窗口里 GC 一跑，键没了，那张图的进度条就再也不动了。
     */
    public fun track(url: String, listener: ProgressListener): ProgressSubscription {
        val key = normalize(url)
        synchronized(lock) {
            listeners.getOrPut(key) { ArrayList(1) }.add(listener)
        }
        return ProgressSubscription(this, key, listener)
    }

    /** [ProgressSubscription.close] 的实现；[key] 已经规范化过。 */
    internal fun untrack(key: String, listener: ProgressListener) {
        synchronized(lock) {
            val bucket = listeners[key] ?: return
            bucket.remove(listener)
            if (bucket.isEmpty()) listeners.remove(key)
        }
    }

    /** 当前有多少个 URL 被订阅着。给测试和调试页看有没有泄漏用。 */
    public val trackedUrlCount: Int
        get() = synchronized(lock) { listeners.size }

    private fun isTracked(key: String): Boolean =
        synchronized(lock) { listeners.containsKey(key) }

    private fun dispatch(key: String, progress: DownloadProgress) {
        // 锁下只拍快照，回调在锁外跑：订阅者在回调里 close 自己是常见写法，不能让它撞锁。
        val snapshot: List<ProgressListener> = synchronized(lock) {
            listeners[key]?.toList() ?: return
        }
        for (listener in snapshot) {
            try {
                listener.onProgress(progress)
            } catch (e: Exception) {
                // 只接 Exception。Error（OOM / StackOverflow）是 VM 真出事了，记一条日志继续下载
                // 只会把问题藏起来 —— 与 app 侧 BufferCorruptionGuardInterceptor 的取舍一致。
                onListenerError(e)
            }
        }
    }

    private fun normalize(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url

    public companion object {
        /** 150ms ≈ 每秒 6~7 次，进度条看着连续，又不至于每个 8KB segment 都去戳一次 UI。 */
        public const val DEFAULT_REFRESH_INTERVAL_MS: Long = 150L
    }
}
