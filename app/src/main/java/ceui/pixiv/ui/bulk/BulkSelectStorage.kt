package ceui.pixiv.ui.bulk

import ceui.lisa.models.IllustsBean
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * 跨 Activity 临时传递 illust 列表的轻量 holder（避免序列化整个 list 走 Intent extras）。
 * 原来的 DataChannel 已删除，这是替代品。
 *
 * 用法：
 *   1. 入口处（IAdapter / TagAdapter 等长按弹批量下载）
 *      → BulkSelectStorage.put(list) → 启动 TemplateActivity("批量选择")
 *   2. BulkSelectV3Fragment.onViewCreated → BulkSelectStorage.consume()
 *      取出列表（同时清空 holder，避免内存泄漏 / 旧数据复用）
 *
 * 防 OOM：[HARD_CAP] 截断超大列表。一个 IllustsBean 在内存里 ~10-15KB，
 * 5 万项就是 750MB 直接爆。
 */
object BulkSelectStorage {

    private const val HARD_CAP = 20_000

    private val pendingItems = AtomicReference<List<IllustsBean>?>()

    fun put(items: List<IllustsBean>) {
        val snapshot = if (items.size > HARD_CAP) {
            Timber.tag("BulkSelectStorage")
                .w("incoming size ${items.size} > HARD_CAP $HARD_CAP, truncating")
            // take() already returns a new list, so a second toList() would
            // copy 20,000 references for no additional isolation.
            items.take(HARD_CAP)
        } else {
            items.toList()
        }
        pendingItems.set(snapshot)
    }

    /** 取出列表并清空 holder。返回 null 说明流程异常（直接退出 fragment）。 */
    fun consume(): List<IllustsBean>? = pendingItems.getAndSet(null)
}
