package ceui.pixiv.imageloader

import android.content.Context
import android.net.Uri
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.DownloadedPageIndex
import ceui.pixiv.snapshot.SNAPSHOT_LOCAL_URL_PREFIX
import ceui.pixiv.snapshot.parseSnapshotLocalUrl
import ceui.pixiv.snapshot.snapshotAssetFile
import java.io.File

/**
 * 「这一页的图从哪来」的**唯一入口**。
 *
 * 在这之前，只有一级详情页 B（IllustAdapter.loadIllust）与二级看图页 C
 * （FragmentImageDetail.loadImage）各自实现了来源判定；条漫、幻灯片、AI 取图等 15 个取图调用点
 * 一律只认网络通道，于是本地明明已下载也要重下一遍。判定写进了渲染流程里（B/C 那两个方法都是
 * 流式渲染流程，判定只是其中一小段），想复用就得先剥离出来 —— 这就是本对象存在的全部理由。
 *
 * 判定顺序（前两条零 IO，与现有显示层的实际顺序一致）：
 * 1. url 自身是本地形态（`shaftsnap://` / `content://` / `file://` / 裸绝对路径）→ [PageImageSource.Local]
 * 2. 查看器任务表已就绪（[ImageLoaderV3.peekFile]）                          → [PageImageSource.Local]
 * 3. 下载库记录（[DownloadedPageIndex] 两段式，含文件名兜底）                 → [PageImageSource.Local]
 * 4. 以上都没有                                                              → [PageImageSource.Remote]
 *
 * **为什么第 1 条在最前**：它是纯字符串判断（最便宜），而且让快照 / 下载详情模式**自动正确**
 * —— 快照是把 url 改写成 `shaftsnap://`（见 [ceui.pixiv.snapshot.SnapshotLocalUrls]），
 * 所以「下载记录不许顶掉存档」这个守卫从此不必任何消费方手写，三边自然一致。
 *
 * **为什么收 [Illust] 而不是 illustId**：第 3 条的兜底要 `FileCreator.customFileName(illust, page)`。
 * 上一版为了「解耦」只收 illustId，结果就是把第 3 条抄漏了 —— 解耦度换不来规则完整性。
 *
 * ⚠️ 第 3 条要查下载库，所以 [resolve] 是 `suspend`，请在协程里调（内部自己切 IO，不会卡主线程）。
 * 需要「同步、零 IO」的判定时用 [resolveCheap]。
 *
 * ⚠️ **本对象有意未被 B（`IllustAdapter.loadIllust`）与 C（`FragmentImageDetail.loadImage`）的渲染流程
 * 采用** —— 它们各自的分流与这里四条规则**等价**，换过来要动回收池 / #912 观察者解绑 / zoomimage 的
 * transform 状态与 large→原图竞态，收益只是形状统一，不划算。两处的逐条对应关系、以及「什么情况下
 * 该回头改」写在它们各自的 KDoc 里 —— 动手改那两处之前先读它们。
 */
object PageImageSourceResolver {

    /**
     * 完整四条规则。**必须从协程调用**（第 3 条查库）。
     *
     * @param illust 作品。url 模式（[ceui.lisa.fragments.FragmentImageDetail] 的 isUrlMode）下为
     *   null，此时跳过第 3 条 —— 没有作品就没有页码可言。
     * @param page 页码，与 [FileCreator.customFileName] 同基准。
     * @param url 这一页的 url；null / 空 → [PageImageSource.Unavailable]。
     * @param allowNetwork false 时第 4 条返回 [PageImageSource.Unavailable]、不做任何网络动作
     *   （圈选翻译松手那一刻用：不能把用户丢进静默等待）。
     */
    suspend fun resolve(
        context: Context,
        illust: Illust?,
        page: Int,
        url: String?,
        allowNetwork: Boolean = true,
    ): PageImageSource {
        if (url.isNullOrEmpty()) return PageImageSource.Unavailable

        localUrlSource(url)?.let { return it }

        ImageLoaderV3.peekFile(url)?.let { file ->
            return PageImageSource.Local(Uri.fromFile(file), file, PageImageSource.Origin.Cached)
        }

        if (illust != null && page >= 0) {
            DownloadedPageIndex.page(context, illust, page)?.let { uri ->
                return PageImageSource.Local(uri, fileOf(uri), PageImageSource.Origin.Downloaded)
            }
        }

        if (!allowNetwork) return PageImageSource.Unavailable
        return PageImageSource.Remote(ImageLoaderV3.obtain(url))
    }

    /**
     * 只做第 1、2 条（纯字符串 + 一次 Map 命中，零 IO、无挂起），命中返回 [PageImageSource.Local]，
     * 未命中返回 null 由调用方决定怎么问下载库。
     *
     * 存在的理由是**渲染热路径**：B 已经用 [DownloadedPageIndex.pages] 批量扫过整部作品，
     * 逐页再查一次库纯属浪费；它只需要「快照 url / 已经下好」这两条。
     */
    fun resolveCheap(url: String?): PageImageSource.Local? {
        if (url.isNullOrEmpty()) return null
        localUrlSource(url)?.let { return it }
        val file = ImageLoaderV3.peekFile(url) ?: return null
        return PageImageSource.Local(Uri.fromFile(file), file, PageImageSource.Origin.Cached)
    }

    /** 第 1 条：url 自身是不是本地形态。不是则返回 null，交给后面的规则。 */
    private fun localUrlSource(url: String): PageImageSource.Local? = when {
        url.startsWith(SNAPSHOT_LOCAL_URL_PREFIX) -> {
            // 快照存档就在私有目录里，能直接给出真实文件 —— 取文件型消费方因此零拷贝，
            // 渲染型消费方也拿得到 Sketch / Glide 都认的 File。
            val file = parseSnapshotLocalUrl(url)?.let { (snapshotId, rel) ->
                snapshotAssetFile(snapshotId, rel)
            }
            PageImageSource.Local(Uri.parse(url), file, PageImageSource.Origin.LocalUrl)
        }

        // 下载详情模式（DoneListV3Fragment 把记录里的 filePath 当 url 塞进来）。
        // content://（MediaStore / SAF）没有真实文件，只有可读流 —— 需要 File 时由
        // PageImageSource.awaitFile() 拷一份，渲染层照旧零拷贝。
        url.startsWith(CONTENT_SCHEME) ->
            PageImageSource.Local(Uri.parse(url), null, PageImageSource.Origin.LocalUrl)

        url.startsWith(FILE_SCHEME) -> {
            val uri = Uri.parse(url)
            PageImageSource.Local(uri, fileOf(uri), PageImageSource.Origin.LocalUrl)
        }

        // 早期记录可能是裸绝对路径而不是 file:// uri。`//` 开头的是协议相对 URL，不算。
        url.startsWith("/") && !url.startsWith("//") -> {
            val file = File(url)
            PageImageSource.Local(
                Uri.fromFile(file),
                file.takeIf { it.isFile },
                PageImageSource.Origin.LocalUrl,
            )
        }

        else -> null
    }

    /** `file://` uri → 真实存在的 File。其它 scheme 一律 null（只有可读流，不在这里拷）。 */
    private fun fileOf(uri: Uri): File? {
        if (uri.scheme != FILE_SCHEME_NAME) return null
        val path = uri.path ?: return null
        val file = File(path)
        return file.takeIf { it.isFile }
    }

    private const val CONTENT_SCHEME = "content://"
    private const val FILE_SCHEME = "file://"
    private const val FILE_SCHEME_NAME = "file"
}