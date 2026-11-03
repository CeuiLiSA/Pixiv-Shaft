package ceui.pixiv.imageloader

/**
 * 一次图片加载请求。
 *
 * [url] 是**唯一身份**:V3 系统按它去重、共享、缓存。因为 Pixiv 的图片 URL 本身就编码了
 * 分辨率档位(square_medium / medium / large / original),所以:
 * - 瀑布流 A 的低分辨率缩略图 URL 与详情/大图的 URL 不同 → 天然是不同任务,互不影响。
 * - 详情页 B 与大图页 C 只要拿的是同一个 URL(通常是 original),就命中同一个任务、
 *   共享同一条进度和同一份下载结果。
 *
 * [isOriginal] 由调用方显式告知该请求是否为原图：
 * - 原图走专用断点续传加载器并存入独立原图缓存池；
 * - 非原图（缩略图、中图、大图等）走通用轻量加载。
 *
 * [name] 用于下载落盘时的文件名/日志展示。
 */
data class ImageRequest(
    val url: String,
    val name: String = url.substringAfterLast('/'),
    val isOriginal: Boolean = false,
) {
    /** 去重键。 */
    val key: String get() = url

    val isValid: Boolean get() = url.isNotEmpty()
}
