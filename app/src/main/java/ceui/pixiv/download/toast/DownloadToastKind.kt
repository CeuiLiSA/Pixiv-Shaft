package ceui.pixiv.download.toast

import androidx.annotation.StringRes
import ceui.lisa.R

/**
 * 「下载相关提示消息」（[ceui.pixiv.ui.settings.DownloadToastDialog]）里可逐项安静的一条消息。
 *
 * 一个枚举值 = 弹窗里的一行开关 = 用户认得出来的一条提示。`name` 就是落进
 * `Settings.getMutedDownloadToasts()` 的键，**改名等于把老用户的选择丢掉**：旧键在新版里读不出来，
 * 那条消息会重新开始弹。
 *
 * 收录口径：下载（含批量、含小说）**过程自己弹出来**的提示 —— 入队、完成、失败、批量汇总、
 * 逐篇进度这一层。下载管理页里用户主动点的工具操作（导入本地下载 / 导出直链 / 重命名文件）
 * 不在此列：那是「点了就有回音」，不是会自己刷屏的噪音。
 */
enum class DownloadToastKind(
    /** 弹窗里这一行的标题。 */
    @StringRes val labelRes: Int,
    /** 弹窗里的分组（图片 / 小说），只影响排版。 */
    val group: Group,
) {

    // ── 图片下载 ──────────────────────────────────────────────────

    /** 「1 个项目已加入下载队列」——详情页 / 卡片 / 多选页的下载入口。 */
    ENQUEUED(R.string.download_toast_enqueued, Group.IMAGE),

    /** 「下载任务创建失败：…」——入队过程抛异常（issue #1105 那条）。 */
    ENQUEUE_FAILED(R.string.download_toast_enqueue_failed, Group.IMAGE),

    /** 「xxx 已下载」——逐张完成。 */
    DOWNLOAD_DONE(R.string.download_toast_download_done, Group.IMAGE),

    /** 「下载失败，原因：…」——逐张失败。 */
    DOWNLOAD_FAILED(R.string.download_toast_download_failed, Group.IMAGE),

    /** aria2 发送成功 / 失败。 */
    ARIA2(R.string.download_toast_aria2, Group.IMAGE),

    /** 「已授权的下载目录不存在」——创建文件失败。 */
    STORAGE_UNAVAILABLE(R.string.download_toast_storage_unavailable, Group.IMAGE),

    /** 「下载记录恢复成功」——冷启动恢复下载队列。 */
    RECORD_RESTORED(R.string.download_toast_record_restored, Group.IMAGE),

    /** 空间不足，下载被自动暂停。 */
    LOW_STORAGE_PAUSED(R.string.download_toast_low_storage_paused, Group.IMAGE),

    /** 批量入队：发起 / 完成 / 失败 / 空 / 截断。 */
    BULK_ENQUEUE(R.string.download_toast_bulk_enqueue, Group.IMAGE),

    /** 「批量下载完成：共 x 个，成功 y，失败 z」——整批跑空时的一条汇总。 */
    BULK_SUMMARY(R.string.download_toast_bulk_summary, Group.IMAGE),

    // ── 小说下载 ──────────────────────────────────────────────────

    /** 小说批量下载的逐篇进度（一篇一条，最吵的那条）。 */
    NOVEL_PROGRESS(R.string.download_toast_novel_progress, Group.NOVEL),

    /** 小说批量下载的整体结果（全部成功 / N 篇失败）。 */
    NOVEL_BATCH_RESULT(R.string.download_toast_novel_batch_result, Group.NOVEL),

    /** 单篇小说保存 / 导出：开始、成功、失败、已存在跳过。 */
    NOVEL_SAVE(R.string.download_toast_novel_save, Group.NOVEL),

    /** 系列小说的下载 / 合并导出结果（含跨系列合并）。 */
    NOVEL_SERIES_EXPORT(R.string.download_toast_novel_series_export, Group.NOVEL),

    /** 收藏后自动下载小说的结果。 */
    NOVEL_AUTO_DOWNLOAD(R.string.download_toast_novel_auto_download, Group.NOVEL),
    ;

    /** 弹窗里的两个分组。 */
    enum class Group { IMAGE, NOVEL }
}