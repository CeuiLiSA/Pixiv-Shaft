package ceui.lisa.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * 已完成 tab 标题上那个数字（作品数）的进程级持有者。
 *
 * ## 为什么不再「每次重算」
 *
 * 旧实现是列表每 collect 一次就 `publishDoneCardCount(groups.size)`，等于要求列表
 * 一直在跑。可列表本身很贵（`SELECT * ... ORDER BY downloadTime DESC LIMIT 600`
 * 再对 600 行做 `illustGson` 反序列化），并发 5 下载时 poke 约每 400ms 一次，
 * 列表追不上 —— 这正是「三个队列页全空白、连空态文案都没有」那条链路的一环。
 *
 * 所以列表被降级成「只在已完成 tab 可见时才跑」（RESUMED）。代价是离开 tab 后没人
 * 算数字了，会冻在最后一次的值。
 *
 * ## 改成增量
 *
 * 写库那一刻就知道自己插了什么：`DownloadDao.insertDownload` 返回「这是不是一张
 * 新卡片」。直接 +1 比事后重算便宜几个数量级，而且**零延迟** —— 5 个下载同时完成
 * 就是 5 次原子自增，全部立刻可见。不需要轮询，也不需要把事件合并成流（合并必然
 * 带来延迟）。
 *
 * ## 两个计数
 *
 * - [cards]：要显示的数字 = 前 [WINDOW] 行能折成多少张卡片
 * - [rows]：表里有多少行，只用来判断「窗口满没满」
 *
 * ## 窗口满了为什么停止增量
 *
 * 卡片数 = 前 [WINDOW] 行去重后的作品数。窗口没满时，插一行要么 +1（该作品的第一
 * 行），要么不变（同一作品的第 N 页）。窗口满了以后，插一行会挤掉最旧的一行；被挤掉
 * 那行如果是某作品的最后一行，数字还得 -1。要判断这个，得知道「新窗口第 600 行的
 * 时间戳」—— 而 `illust_download_table` 在 #1288 之前没有 `downloadTime` 索引，
 * 查它就是那条要全表排序的语句。
 *
 * 所以这里选择**冻结**：`rows >= WINDOW` 之后不再增量，等下一次对账覆盖。对账发生在
 * 进下载管理页（`DownloadManagerSharedViewModel.refreshDoneCardCount`）和已完成列表
 * 加载完（[reconcile]）时。
 *
 * **#1288 合入后这里可以改成精确增量**：有了 `downloadTime` 索引，「新窗口最旧一行
 * 的时间戳」变成一次索引 seek，再补一次 `illustId` 索引点查就能判断被挤掉那行是不是
 * 该作品的最后一行，[onRowInserted] 里的 `if (n > WINDOW) return` 就是唯一要删的地方。
 *
 * 冻结的表现是「数字停在旧值直到下次对账」，不是「越滚越大」—— 旧值可能偏小，但不会
 * 变成一个错得很离谱的数。
 */
object DoneCardCounter {

    /**
     * 已完成列表一次加载的行数上限。必须与 `DoneListV3Fragment` 取数用的 LIMIT 一致，
     * 否则「数字 = 卡片数」这条不变式会从定义上就破掉。
     */
    const val WINDOW = 600

    private val _cards = MutableStateFlow(0)
    val cards: StateFlow<Int> = _cards.asStateFlow()

    /**
     * 表里的行数。插入时 +1、对账时重置为真实值；**删除不必减** —— 删除只可能发生在
     * 已完成 tab 内（[ceui.pixiv.ui.download.DoneListV3Fragment] 的删除入口），紧接着
     * 就有一次对账把它重置。偏大只会让增量更早停下，方向是保守的。
     */
    private val rows = AtomicInteger(0)

    /** 还没对账过时增量的基准是未知的（rows 从 0 起算会得出荒谬的小数字），直接跳过。 */
    @Volatile
    private var initialized = false

    /**
     * 落了一行。[isNewCard] 由 `DownloadDao.insertDownload` 判定并返回 —— 那里已经在
     * 归一化 illustId，顺手做一次索引点查最省。
     *
     * 用 [MutableStateFlow.update] 而不是 `value = value + 1`：并发 5 下载同时完成时
     * 读-改-写会丢更新，这里必须是 CAS。
     */
    @JvmStatic
    fun onRowInserted(isNewCard: Boolean) {
        if (!initialized) return
        val n = rows.incrementAndGet()
        if (n > WINDOW) return
        if (isNewCard) _cards.update { it + 1 }
    }

    /**
     * 对账：用一次真实查询的结果覆盖两个计数。
     *
     * 调用点是「已完成列表加载完」（`groups.size` + 实际行数）和「进下载管理页时的一次
     * 轻量查询」（见 `DownloadManagerSharedViewModel.refreshDoneCardCount`）。
     * 覆盖方向永远是库里的真相优先，增量期间的少量偏差在这里被抹掉。
     */
    @JvmStatic
    fun reconcile(cards: Int, rowCount: Int) {
        rows.set(rowCount)
        _cards.value = cards
        initialized = true
    }

    /**
     * 直接设数字、不动行数 —— 给「乐观更新」用（删一条后立刻改数字，不等列表重算）。
     * 最终仍以对账为准。
     */
    @JvmStatic
    fun set(count: Int) {
        _cards.value = count
    }

    /** 有没有可靠的对账基准。false 表示进下载管理页时要先查一次。 */
    @JvmStatic
    fun needsReconcile(): Boolean = !initialized

    /**
     * 标脏：有人绕过了 `DownloadDao.insertDownload` 直接改表 —— 设置页的
     * 「清除批量下载关联数据」（`BulkDownloadCacheCleaner.wipe`）和批量导入
     * （`DownloadImporter` 走 `insertIgnoreAll`）。增量看不见这些变化，下一次进下载
     * 管理页时对账一次纠正。
     */
    @JvmStatic
    fun markStale() {
        initialized = false
    }
}
