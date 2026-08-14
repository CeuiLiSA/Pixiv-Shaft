package ceui.pixiv.ui.bulk

import android.os.Process
import ceui.lisa.activities.Shaft
import ceui.lisa.cache.Cache
import ceui.lisa.file.LegacyFile
import ceui.lisa.http.Retro
import ceui.lisa.models.GifResponse
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.UgoiraDownloadRecord
import ceui.pixiv.download.config.DownloadItems
import com.blankj.utilcode.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.coroutineContext

/**
 * Ugoira 单条全链路 suspend 任务：
 *   getGifPackage 拿 zip url + frame delays
 *   → 下载 zip 到 [LegacyFile.gifZipFile]（仅 internal cache）
 *   → 解压到 [LegacyFile.gifUnzipFolder]（仅 internal cache）
 *   → 按「动图保存格式」出片(mp4 / GIF)**直接写进 V3 [DownloadsRegistry] 的 WriteHandle.stream**
 *
 * 设计要点：
 *
 *  - **不走 [ceui.lisa.file.OutPut.outPutUgoira]**：那个 helper 内部虽然也用 V3 facade，
 *    但会在批量场景里给每条 ugoira 弹 save_gif_success / save_gif_exists toast，
 *    几十条 ugoira 一起跑会刷屏。这里直接调 [DownloadsRegistry.downloads.open]
 *    拿 WriteHandle，跳过 toast。
 *
 *  - **不写 [LegacyFile.gifResultFile]** 中间文件：旧路径会编一份到 cache 再
 *    `outPutGif` 复制到用户目录，双写一次 IO。这里编码器输出流直接接 V3 WriteHandle，
 *    一次写盘到目标位置，按用户的 ugoira 命名预设落到对应的 [Bucket.Ugoira] 目录。
 *
 *  - **每一步 idempotent**：已下好的 zip / 已解压的 frames 不重做；
 *    [DownloadsRegistry.downloads.open] 在 OverwritePolicy.Skip + 已存在时返回 null，
 *    我们跳过不报错，把这条当 SUCCESS。冷启动把 DOWNLOADING 翻 PENDING 重跑时
 *    第二遍几乎瞬间完成。
 *
 *  - 任何一步出错就抛异常 —— [QueueDownloadManager.dispatchUgoira] 走 retry / FAILED
 *    路径，不要在这里吞错误。
 */
/**
 * 下载并编码单条 ugoira。
 *
 * - [encodeSem] 用来串行 GIF 编码这一步（吃满帧 Bitmap，并行多了 OOM）。其它阶段
 *   （meta / zip 下载 / 解压）是 IO-bound，不需要互斥；调用方传 `Semaphore(1)`
 *   就能"并发下载，串行编码"，让 maxConcurrent 个 ugoira 同时在 pipeline 上跑。
 *   单条调用（详情页保存）不在意并发可以省略，默认 [Semaphore](Int.MAX_VALUE)
 *   等价于无锁。
 */
suspend fun downloadUgoira(
    illust: IllustsBean,
    encodeSem: Semaphore = Semaphore(Int.MAX_VALUE),
    onPhase: (UgoiraPhase) -> Unit = {},
) = withContext(Dispatchers.IO) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    val ctx = Shaft.getContext()
    val illustId = illust.id

    Timber.tag(TAG).i("[UGOIRA] start illust=$illustId title=${illust.title}")

    // 播放引擎已落盘的帧序列 → 直接出片写进目标(V3 WriteHandle),跳过 meta/下载/解压。
    //
    // 格式随「动图保存格式」设置:mp4 那份播放缓存里多半已经压好(用户看过),保存就是纯拷贝;
    // GIF 则现编 —— 播放链路不再产出 GIF,「保存」是唯一需要它的地方。用户在详情页看过的话,
    // 帧已经在盘上(补帧版优先),这里省掉的是下载 + 解压 + 补帧。
    suspend fun encodeFromFrames(frames: UgoiraFrames) {
        // 出片格式随设置。mp4 那份多半已经在播放缓存里(用户看过),这时候只是纯拷贝;
        // 压不出来会降级成 GIF —— 所以 handle 必须按**实际产物**开,不能按设置开。
        val export = UgoiraEngine.exportForSave(illust)
        val asVideo = export?.isVideo == true
        val handle = DownloadsRegistry.downloads.open(DownloadItems.ugoira(illust, asVideo))
        if (handle == null) {
            Timber.tag(TAG).i("[UGOIRA] skip: target already exists illust=$illustId")
            if (export?.temporary == true) runCatching { export.file.delete() }
            return
        }
        try {
            encodeSem.withPermit {
                BufferedOutputStream(handle.stream).use { bos ->
                    if (export != null) {
                        BufferedInputStream(FileInputStream(export.file)).use { bis -> bis.copyTo(bos) }
                    } else {
                        encodeFramesToGif(frames.files, frames.delaysMs, bos)
                    }
                }
            }
            handle.onFinish()
        } catch (t: Throwable) {
            runCatching { handle.onAbort() }
            throw t
        } finally {
            if (export?.temporary == true) runCatching { export.file.delete() }
        }
        // onFinish 之后的收尾必须落在 try 之外：这个 catch 会 onAbort，而 MediaStore 后端的
        // onAbort 是 contentResolver.delete(uri) —— 已经 commit 的成品在里面抛一次就被删了。
        UgoiraDownloadRecord.record(illust, handle.uri, asVideo)
        Timber.tag(TAG).i("[UGOIRA] done via 播放引擎帧序列 illust=$illustId (${frames.files.size}帧, rife=${frames.interpolated}) uri=${handle.uri}")
    }

    // 0) 用户在详情页看过的话,帧序列已经在盘上(补帧版优先)。直接编,省掉 meta/下载/解压/补帧。
    UgoiraEngine.peekPlayableFrames(illust)?.let { frames ->
        onPhase(UgoiraPhase.ENCODE)
        encodeFromFrames(frames)
        return@withContext
    }

    // 1) 元数据：zip url + frame delays。Cache 里如果已经有 GifResponse 直接复用。
    onPhase(UgoiraPhase.FETCH_META)
    val cached = runCatching {
        Cache.get().getModel(Params.ILLUST_ID + "_" + illustId, GifResponse::class.java)
    }.getOrNull()
    val resp: GifResponse = if (cached?.ugoira_metadata != null) {
        cached
    } else {
        val fetched = Retro.getAppApi().getGifPackage(illustId).awaitFirstSafe()
        runCatching { Cache.get().saveModel(Params.ILLUST_ID + "_" + illustId, fetched) }
        fetched
    }
    val zipUrl = resp.ugoira_metadata?.zip_urls?.medium
        ?: throw IllegalStateException("ugoira zip url missing for illust=$illustId")
    coroutineContext.ensureActive()

    // 2-4) zip 下载 / 解压 / 编码全程握 per-illust 文件锁,与播放引擎互斥 —— 两边共写
    //    同一 zip/.part/解压目录,「边看边存同一条」无锁并发写会把 zip 持久写坏。
    //
    //    等锁期间播放引擎可能刚把帧序列落好(用户正在看),那就没必要重下重解。但出片要
    //    **出了锁再做**:[UgoiraEngine.exportForSave] 自己会拿同一把 per-illust 锁(它可能
    //    得现压一份 mp4),而 kotlinx 的 Mutex 不可重入 —— 在锁里调就是死锁。
    val lateFrames: UgoiraFrames? = UgoiraEngine.fileLockFor(illustId).withLock {
        UgoiraEngine.peekPlayableFrames(illust)?.let { return@withLock it }

        // 2) 下载 zip（已存在且非空就跳过）—— internal cache，未来由 V3 cache 清理
        val zipFile = LegacyFile.gifZipFile(ctx, illust)
        if (!zipFile.isFile || zipFile.length() == 0L) {
            onPhase(UgoiraPhase.DOWNLOAD_ZIP)
            downloadZipTo(zipUrl, zipFile)
        } else {
            Timber.tag(TAG).i("[UGOIRA] zip already cached ($zipFile)")
        }
        coroutineContext.ensureActive()

        // 3) 解压。完整性按 frames.size 比对：上次进程死在解压途中，folder 里可能有
        //    不全的帧子集，第二次跑直接 skip 解压会编出残废 GIF。
        //    数对不上 → 清空重解；zip 在本地 cache，几十毫秒级别开销，便宜。
        val unzipFolder = LegacyFile.gifUnzipFolder(ctx, illust)
        val expectedFrameCount = resp.ugoira_metadata?.frames?.size ?: 0
        val onDiskFrameCount = unzipFolder.listFiles()?.count { it.isFile } ?: 0
        if (onDiskFrameCount == 0 || (expectedFrameCount > 0 && onDiskFrameCount != expectedFrameCount)) {
            if (onDiskFrameCount > 0) {
                // 删现有内容，但保留 folder 本身
                unzipFolder.listFiles()?.forEach { runCatching { it.delete() } }
                Timber.tag(TAG).w("[UGOIRA] frame count mismatch (had=$onDiskFrameCount expect=$expectedFrameCount), re-extracting")
            }
            onPhase(UgoiraPhase.EXTRACT)
            try {
                ZipUtils.unzipFile(zipFile, unzipFolder)
            } catch (t: Throwable) {
                // 解压失败大概率 zip 本身坏了:删 zip 再抛,重试/下次重新下载(自愈)
                runCatching { zipFile.delete() }
                throw t
            }
            Timber.tag(TAG).i("[UGOIRA] unzipped ${unzipFolder.listFiles()?.size ?: 0} frames")
        }
        coroutineContext.ensureActive()

        // 4) 直接编进 V3 WriteHandle —— 用户配置的 ugoira 命名模板 / 存储位置统一生效。
        //    ENCODE phase 在 encodeSem.withPermit 里跑：等许可期间 last-emitted phase 仍是
        //    上一步（DOWNLOAD_ZIP / EXTRACT），UI 显示是诚实的——它真的还没在 encode。
        encodeSem.withPermit {
            onPhase(UgoiraPhase.ENCODE)
            // 这条链路是「没看过的动图批量下载」:帧就是刚解压出来的原速帧(和以前出 GIF
            // 用的是同一批,不含补帧)。mp4 压到临时文件再拷,压不出来就照旧出 GIF。
            val tempVideo = if (Shaft.sSettings.isUgoiraSaveAsMp4()) {
                val srcFrames = sortedUgoiraFrames(unzipFolder)
                UgoiraVideoEncoder.encode(
                    File(ctx.cacheDir, "ugoira_save_$illustId.mp4"),
                    srcFrames, ugoiraDelays(srcFrames.size, resp),
                )
            } else {
                null
            }
            val handle = DownloadsRegistry.downloads.open(DownloadItems.ugoira(illust, tempVideo != null))
            if (handle == null) {
                // OverwritePolicy.Skip + 目标已存在；当作完成
                Timber.tag(TAG).i("[UGOIRA] skip: target already exists illust=$illustId")
                runCatching { tempVideo?.delete() }
                return@withPermit
            }
            try {
                BufferedOutputStream(handle.stream).use { bos ->
                    if (tempVideo != null) {
                        BufferedInputStream(FileInputStream(tempVideo)).use { bis -> bis.copyTo(bos) }
                    } else {
                        encodeFramesToGif(unzipFolder, resp, bos)
                    }
                }
                handle.onFinish()
            } catch (t: Throwable) {
                // onAbort 让 backend 清掉部分写入的 .pending-NNNN 文件；不调用就会留 0 字节孤儿
                runCatching { handle.onAbort() }
                throw t
            } finally {
                runCatching { tempVideo?.delete() }
            }
            // 同 encodeFromFrames：写记录在 try 之外，别让收尾动作有机会触发 onAbort 把
            // 已经 commit 的成品删掉。
            UgoiraDownloadRecord.record(illust, handle.uri, tempVideo != null)
            Timber.tag(TAG).i("[UGOIRA] done illust=$illustId uri=${handle.uri}")
        }

        // 5) 成品已落到用户目录 → cache 里的 zip / 解压帧就是死重量,趁还握着文件锁删掉。
        //    gif cache 没有自动淘汰,批量下载几百条 ugoira 不清就是几十 GB。异常路径不会
        //    走到这(直接抛出 withLock),中间产物留给重试复用。
        UgoiraEngine.discardIntermediates(illustId, zipFile, unzipFolder)
        null // 本轮自己出完片了,不用再走下面那段
    }

    // 等锁期间播放引擎抢先落好了帧序列:锁已释放,现在安全地由帧出片。
    lateFrames?.let { frames ->
        onPhase(UgoiraPhase.ENCODE)
        encodeFromFrames(frames)
    }
}

// zip 下载 / 帧编码 / OkHttp client 已提到 [UgoiraEngine].kt 的 top-level（internal），
// 保存链路(本文件)与播放链路([UgoiraEngine])共用同一份,别再各留一份拷贝。
private const val TAG = "UgoiraTask"
