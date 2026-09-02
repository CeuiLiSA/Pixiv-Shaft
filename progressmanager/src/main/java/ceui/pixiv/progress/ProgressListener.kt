package ceui.pixiv.progress

/**
 * 下载进度的接收方。
 *
 * **在读响应体的那个线程上被调用**（OkHttp 的 dispatcher 线程，或 Glide 的解码线程），
 * 不会切到主线程。回调必须便宜且线程安全 —— 典型做法是往 `StateFlow` 里写一个值，
 * 想切线程由消费方自己做。这样模块不用带 Handler / Looper，也就能在纯 JVM 上测。
 *
 * 回调抛出的异常不会打断下载，见 [ProgressTracker] 的 `onListenerError`。
 */
public fun interface ProgressListener {

    public fun onProgress(progress: DownloadProgress)
}
