package ceui.pixiv.download

import android.content.Intent
import android.net.Uri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.activities.Shaft
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.pixiv.download.config.DownloadItems
import timber.log.Timber

/**
 * 动图（ugoira）成品 GIF 落盘后往 `illust_download_table` 记一笔。
 *
 * 之前「已完成」里动图那张卡记的是 [ceui.lisa.core.Manager] 下载完中间 zip 时插的行，
 * filePath 指向 app cache 里的 `xxx.zip`（issue #920）—— 点进去等于拿压缩包当图片解码，
 * 一片黑；而真正编出来的 `.gif` 反而从没进过库，批量队列下的动图更是压根不出现在列表里。
 *
 * 现在中间 zip 不再写记录，改由 GIF 真正写完的三个出口（[ceui.lisa.file.OutPut.outPutGif]
 * 与 [ceui.pixiv.ui.bulk.downloadUgoira] 的两条编码路径）调这里补一行。
 *
 * fileName 走 [DownloadItems.ugoira] 的模板渲染名 —— 与 `RenameSweeper` 判定「这行该叫
 * 什么」用的是同一个函数（[ceui.pixiv.download.maintenance.RenameSweeper]），新写的行天然
 * 就是 alreadyMatching，不会被改名扫描当成待处理项。
 *
 * 写库失败不该拖垮一次已经成功的保存 —— 文件躺在盘上是既成事实，缺的只是「已完成」里
 * 的一条记录，与 [ceui.lisa.core.Manager] 那边同一处理原则。
 */
object UgoiraDownloadRecord {

    /** @param uri GIF 成品的最终位置（content:// 或 file://）。须在 IO 线程调用。 */
    @JvmStatic
    fun record(illust: IllustsBean, uri: Uri) {
        runCatching {
            val entity = DownloadEntity().apply {
                fileName = DownloadsRegistry.downloads
                    .resolvePath(DownloadItems.ugoira(illust)).filename
                filePath = uri.toString()
                illustId = illust.id.toLong()
                // 动图没有分页，成品就是唯一那一「页」。留 -1 会被 DownloadPageBackfill
                // 反复捞起来重解析文件名。
                page = 0
                downloadTime = System.currentTimeMillis()
                illustGson = Shaft.sGson.toJson(illust)
            }
            AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertDownload(entity)
            ManagerReactive.pokeDoneTable()
            // 详情页的下载 FAB 靠这条广播翻成「已下载」。以前它由中间 zip 落盘时发出 ——
            // 那会儿 GIF 还没编出来，徽标是抢跑的；现在发在成品真正写完之后。
            LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(
                Intent(Params.DOWNLOAD_FINISH).putExtra(Params.CONTENT, entity),
            )
        }.onFailure {
            Timber.w(it, "[UGOIRA] 下载记录写入失败 illust=%d", illust.id)
        }
    }
}
