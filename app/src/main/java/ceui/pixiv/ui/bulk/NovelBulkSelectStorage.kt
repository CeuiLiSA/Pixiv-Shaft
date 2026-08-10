package ceui.pixiv.ui.bulk

import ceui.loxia.Novel
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * [NovelBulkSelectV3Fragment] 的列表 holder，语义与 [BulkSelectStorage] 一致
 *（跨 Activity 传列表，避免序列化整个 list 走 Intent extras），只是装 [Novel]。
 *
 * 刻意**不**和 [BulkSelectStorage] 合并成一个装 sealed 类型的 holder：泛型擦除后
 * `put(List<IllustsBean>)` 和 `put(List<Novel>)` 是同一个 JVM 签名，合并要么改名、
 * 要么包一层 sealed 壳，而两条链路本来就没有任何共用的下游 —— 合并只会让插画侧
 * 多背一个它永远用不到的分支。
 */
object NovelBulkSelectStorage {

    /** 一个 [Novel] 带 caption 和 tags，量级同 IllustsBean，同样要防 OOM。 */
    private const val HARD_CAP = 20_000

    private val pendingItems = AtomicReference<List<Novel>?>()

    fun put(items: List<Novel>) {
        val snapshot = if (items.size > HARD_CAP) {
            Timber.tag("NovelBulkSelectStorage")
                .w("incoming size ${items.size} > HARD_CAP $HARD_CAP, truncating")
            items.take(HARD_CAP)
        } else {
            items.toList()
        }
        pendingItems.set(snapshot)
    }

    /** 取出列表并清空 holder。返回 null 说明流程异常（直接退出 fragment）。 */
    fun consume(): List<Novel>? = pendingItems.getAndSet(null)
}
