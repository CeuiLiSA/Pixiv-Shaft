package ceui.pixiv.imageloader

import android.net.Uri
import java.io.File

/**
 * 一页图的**唯一来源描述**：「这一页的图从哪来」这个问题在全仓只有这一个答案类型。
 *
 * 为什么需要它：同一套「本地有没有这一页」的判定，此前在
 * [ceui.lisa.adapters.IllustAdapter]（批量）与 [ceui.lisa.fragments.FragmentImageDetail]（单页）
 * 各写了一份；而条漫、幻灯片、AI 取图这些消费方**完全不知道**还有「本地下载」这条路，
 * 本地明明躺着文件也重新走网络。判定散在渲染流程里，每加一个消费方就再抄一遍、再漏一遍。
 *
 * 判定统一收进 [PageImageSourceResolver]，消费方只认三种形状：
 *
 * - [Local]       本地已有，直接渲染 / 直读，**零拷贝**
 * - [Remote]      需要网络，交出共享任务（渲染型观察进度，取文件型用 `awaitFile()`）
 * - [Unavailable] url 都解析不出来（restricted / deleted / 精简记录）
 *
 * ⚠️ **只在内存里流转**：本类型持有 [Uri] 与 [File]，不可序列化、不进 `Bundle`、不持久化。
 * 需要跨进程或跨配置变更传递时，传回原始 `url` 重新 [PageImageSourceResolver.resolve]。
 */
sealed interface PageImageSource {

    /**
     * 本地已有，可直接渲染。
     *
     * @param uri 渲染层直读用（`file://` / `content://` / `shaftsnap://`）。`shaftsnap://` 由
     *   [ceui.pixiv.snapshot.SnapshotLocalImageLoader] 这个 Glide loader 消费，直接喂
     *   `imageView.loadImage(uri)` 即可，不必自己解包。
     * @param file 仅在能给出**真实文件**时非空（`file://` 与快照存档）。`content://`
     *   （MediaStore / SAF）为 null —— 它只有可读流，硬要文件就得拷一份，那是
     *   [awaitFile] 的事，不是这里的事。
     * @param origin 来源标签，只用于日志与埋点；消费方**不该据此分支**，否则等于把规则又抄了一遍。
     */
    data class Local(val uri: Uri, val file: File?, val origin: Origin) : PageImageSource {

        /**
         * 渲染层该喂给图片库的句柄。**恒定是 [Uri]**，所以 Sketch（`loadImage(uri)`）和
         * Glide（`load(uri)`）都能直接吃，不必让消费方各自判断类型。
         *
         * 为什么不能无条件用 [uri]：`shaftsnap://` 是自有 scheme，只有 Glide 侧注册了
         * [ceui.pixiv.snapshot.SnapshotLocalImageLoader]；大图页 C 与条漫走的是 Sketch，它认不出
         * 这个 scheme，会从「读存档」变成「直接失败」。而快照存档本来就能解析出真实 File，
         * 所以能给出 [file] 时统一转成 `file://`，两条渲染链都认、也不多拷一个字节。
         */
        val renderModel: Uri get() = file?.let { Uri.fromFile(it) } ?: uri
    }

    /**
     * 需要网络。交出的是**共享任务**而不是 `File` —— 渲染型消费方本来就要
     * `observe(task.state)` 拿进度与 `Success` / `Error` 分阶段，形状完全不变，因此能无损接入。
     */
    data class Remote(val task: ImageLoadTask) : PageImageSource

    /** url 都解析不出来（restricted / deleted / 精简记录）。UI 由消费方决定。 */
    data object Unavailable : PageImageSource

    /** 只用于日志与埋点。 */
    enum class Origin {
        /** url 本身就是本地形态：快照 `shaftsnap://`、下载详情模式的 `content://` / 文件路径。 */
        LocalUrl,

        /** 下载库里有这一页的记录，见 [ceui.pixiv.download.DownloadedPageIndex]。 */
        Downloaded,

        /** 查看器任务表里已经下好，见 [ImageLoaderV3.peekFile]。 */
        Cached,
    }
}