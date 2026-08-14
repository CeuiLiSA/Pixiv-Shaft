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
 * 动图（ugoira）成品落盘后往 `illust_download_table` 记一笔。
 *
 * 之前「已完成」里动图那张卡记的是 [ceui.lisa.core.Manager] 下载完中间 zip 时插的行，
 * filePath 指向 app cache 里的 `xxx.zip`（issue #920）—— 点进去等于拿压缩包当图片解码，
 * 一片黑；而真正编出来的 `.gif` 反而从没进过库，批量队列下的动图更是压根不出现在列表里。
 *
 * 现在中间 zip 不再写记录，改由成品真正写完的三个出口（[ceui.lisa.file.OutPut.outPutUgoira]
 * 与 [ceui.pixiv.ui.bulk.downloadUgoira] 的两条出片路径）调这里补一行。
 *
 * fileName 走 [DownloadItems.ugoira] 的模板渲染名 —— 与 `RenameSweeper` 判定「这行该叫
 * 什么」用的是同一个函数（[ceui.pixiv.download.maintenance.RenameSweeper]），新写的行天然
 * 就是 alreadyMatching，不会被改名扫描当成待处理项。
 *
 * 写库失败不该拖垮一次已经成功的保存 —— 文件躺在盘上是既成事实，缺的只是「已完成」里
 * 的一条记录，与 [ceui.lisa.core.Manager] 那边同一处理原则。
 */
object UgoiraDownloadRecord {

    /**
     * **本函数不抛异常，这是调用方依赖的契约。**
     * 它跑在 `handle.onFinish()` 之后，而各 backend 的 `onAbort()` 会删掉目标
     * （MediaStore 那条是 `contentResolver.delete(uri)`）。从这里漏出去一个异常，
     * 若落进调用方那种「catch → onAbort」的收尾里，被删的就是刚 commit 成功的成品。
     * 调用方另外把它排在 try 之外双保险，两道都别拆。
     *
     * @param uri 成品的最终位置（content:// 或 file://）。须在 IO 线程调用
     *            （Room 主线程写会抛，抛了也只是丢一条记录，不会崩）。
     * @param asMp4 这次**实际**写出去的是 mp4 还是 GIF。必须由调用方按真实产物传：
     *            设置成 mp4 但压制失败时保存链路会降级出 GIF，跟着设置读就会记下一个
     *            `.mp4` 的名字、指着一个 `.gif` 文件，改名扫描和「已下载」判定都会错。
     */
    @JvmStatic
    fun record(illust: IllustsBean, uri: Uri, asMp4: Boolean) {
        runCatching {
            val entity = DownloadEntity().apply {
                fileName = DownloadsRegistry.downloads
                    .resolvePath(DownloadItems.ugoira(illust, asMp4)).filename
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
            Timber.w(it, "[UGOIRA] 下载记录写入 / 完成通知失败 illust=%d", illust.id)
        }
    }
}
