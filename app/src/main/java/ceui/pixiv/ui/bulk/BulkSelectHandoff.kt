package ceui.pixiv.ui.bulk

import ceui.loxia.Illust
import ceui.loxia.Novel
import timber.log.Timber
import java.util.UUID

/**
 * 跨 Activity 交接「待批量操作的列表」的进程级信箱（避免序列化整个 list 走 Intent extras；
 * 原来的 DataChannel 已删除，这是替代品）。
 *
 * 与 [ceui.lisa.fragments.RecmdUserHandoff] 同款的**按 key 交接**，而不是单槽全局变量：
 * 单槽意味着两处入口先后 put（平板双栏、快速连点、Activity 重建重放）后者会覆盖前者，
 * 而先拉起的那页 consume 到的是别人的列表。这里 `put` 返回随机 key，key 随 Bundle 进
 * 目标 Fragment，`take` 一次即删；有限的 [maxPending] 保证忘记 take 也不会攒下无限个
 * 上万条的快照。
 *
 * 防 OOM：[hardCap] 截断超大列表。一个 Illust / Novel 在内存里 ~10-15KB，5 万项就是 750MB 直接爆。
 *
 * 用法：
 *   1. 入口处 → `val key = IllustBulkSelectHandoff.put(list)` → Intent 里 `putExtra(ARG_HANDOFF_KEY, key)`
 *   2. 目标 Fragment.onViewCreated → `take(key)`（返回 null 说明流程异常，直接退出）
 */
class BulkSelectHandoff<T : Any>(
    private val tag: String,
    private val hardCap: Int = 20_000,
    private val maxPending: Int = 4,
) {
    private val pending = LinkedHashMap<String, List<T>>()

    @Synchronized
    fun put(items: List<T>): String {
        val snapshot = if (items.size > hardCap) {
            Timber.tag(tag).w("incoming size ${items.size} > HARD_CAP $hardCap, truncating")
            // take() already returns a new list, so a second toList() would
            // copy 20,000 references for no additional isolation.
            items.take(hardCap)
        } else {
            items.toList()
        }
        val key = UUID.randomUUID().toString()
        pending[key] = snapshot
        while (pending.size > maxPending) {
            pending.remove(pending.keys.first())
        }
        return key
    }

    /** 取出并删除。返回 null = key 未知 / 已被取走 / 已被淘汰。 */
    @Synchronized
    fun take(key: String?): List<T>? = key?.let { pending.remove(it) }

    companion object {
        /** 目标 Fragment 从 arguments 里读 key 用的统一 Bundle 键。 */
        const val ARG_HANDOFF_KEY = "bulk_select_handoff_key"
    }
}

/**
 * 插画 / 小说各一份实例，而不是一个装 sealed 类型的信箱：两条链路没有任何共用的下游，
 * 合并只会让插画侧多背一个它永远用不到的分支。
 */
@JvmField
val IllustBulkSelectHandoff = BulkSelectHandoff<Illust>("IllustBulkSelectHandoff")

/** 一个 [Novel] 带 caption 和 tags，量级同 Illust，同样要防 OOM。 */
@JvmField
val NovelBulkSelectHandoff = BulkSelectHandoff<Novel>("NovelBulkSelectHandoff")
