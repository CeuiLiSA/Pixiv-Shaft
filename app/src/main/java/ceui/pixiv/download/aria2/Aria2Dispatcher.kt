package ceui.pixiv.download.aria2

import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.utils.Params
import ceui.pixiv.download.config.DownloadItems
import java.io.IOException

/**
 * 把 [DownloadItem] 转发给远端 aria2 的胶水层（#692）。
 *
 * 启用条件：Settings.aria2Enabled 且 RPC 地址非空。启用后
 * [ceui.lisa.core.Manager] 的 downloadOne 不再做本地下载，改调 [dispatch]，
 * RPC 成功即视为该任务完成（实际下载由远端 aria2 执行）。
 */
object Aria2Dispatcher {

    private val client = Aria2Client()

    /**
     * [item] 是否应该转发给 aria2。
     *
     * 动图（ugoira）永远返回 false —— 它的 zip 下载是「落到 app cache 给本地
     * 播放器 / GIF 合成器用」的内部操作（FragmentSingleUgora 播放、
     * PixivOperate.unzipAndPlay 合成都依赖本地 zip 文件），转发给 aria2 会让
     * 动图永远无法播放；用户最终保存的 GIF 也是本地合成产物，aria2 代劳不了。
     * 动图始终走本地下载。
     */
    @JvmStatic
    fun shouldDispatch(item: DownloadItem): Boolean {
        if (item.illust.isGif()) return false
        return isEnabled()
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        val settings = Shaft.sSettings ?: return false
        return settings.isAria2Enabled && settings.aria2RpcUrl.isNotBlank()
    }

    /**
     * 同步把 [item] 发给 aria2，返回 aria2 任务 GID。失败抛 [IOException]。
     * 必须在 IO 线程调用。调用方需先用 [shouldDispatch] 过滤。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun dispatch(item: DownloadItem): String {
        val settings = Shaft.sSettings
        return client.addUri(
            rpcUrl = settings.aria2RpcUrl.trim(),
            secret = settings.aria2RpcSecret.trim(),
            fileUrl = item.url,
            // out = 用户当前命名模板渲染出的完整相对路径（目录 + 文件名），
            // 让 NAS 上的目录结构 / 文件名与本地下载完全一致
            out = DownloadItems.illustRelativePath(item.illust, item.index).joinTo("/"),
            dir = settings.aria2RemoteDir.trim(),
            // pixiv 图片 CDN 必须带 Referer，否则 403 —— 跟本地下载用同一个值
            headers = listOf("${Params.MAP_KEY}: ${Params.IMAGE_REFERER}"),
        )
    }

    /** 设置页「测试连接」：返回 aria2 版本号，失败抛 [IOException]。必须在 IO 线程调用。 */
    fun testConnection(rpcUrl: String, secret: String): String =
        client.getVersion(rpcUrl.trim(), secret.trim())
}
