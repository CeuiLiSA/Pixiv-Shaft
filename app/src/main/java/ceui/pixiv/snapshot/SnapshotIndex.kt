package ceui.pixiv.snapshot

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.activities.Shaft
import ceui.lisa.core.JavaAsync
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「这件作品本地有没有离线快照」的索引，键是 `illustId`。**两层：进程内 Map + MMKV 持久表**。
 *
 * ## 为什么必须有索引
 *
 * 快照目录名是随机 UUID（[SnapshotGenerator] 生成时现取），`illustId` 只写在
 * `manifest.json` / `auto_manifest.json` 里 —— 想从作品 id 反查快照，除了把整库 manifest
 * 读一遍没有别的办法。而卡片绑定（[ceui.pixiv.ui.common.staggerIllustRenderer]）是滚动热路径，
 * fling 时每秒几十次，绝不可能在那上面碰磁盘。所以：**内存 Map 供 bind 读，持久表供冷启动读**。
 *
 * ## 持久表：illustId → UUID
 *
 * MMKV 里一个 key 存整张表（`[PersistedEntry]` 的 JSON 数组，一行一个快照，几十 KB 量级）。
 * 选它而不是 Room：这张表只做**单键反查**，不需要按条件筛/排序/join，而 Room 要为它加
 * `AppDatabase` 版本 + 迁移，成本是永久的。整表重写是 O(n)，n 是快照数（常见几十到几百），
 * mmap 写几十微秒，不值得为它换一个存储。
 *
 * ⚠️ 这张表**不进备份、不进云端**。它只是本机缓存 —— 表里的 UUID 指向的目录在别的设备上并不
 * 存在，带过去只会得到一堆点开就报错的条目。两道保证：
 * - `res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml`（cloud-backup 与
 *   device-transfer 两段）都排除了 `mmkv/snapshot_index_v1` 及其 `.crc`；
 * - 应用内备份 / 云端同步（`BackupUtils` / `MoonSync`）只导 Settings + 若干 Room 表，天然不含
 *   MMKV 文件。
 * 新增 MMKV 命名空间时别漏掉上面那两条排除。
 *
 * ## 冷启动只做 stat，不遍历清单
 *
 * 有了持久表，冷启动的代价从「解析全库 manifest」降到：
 * 1. 读表（一次 MMKV 读）；
 * 2. 对表里每条做**两次 stat**（目录在不在 + 对应那个 manifest 文件在不在）—— 目录被删就丢掉这条；
 * 3. `listFiles()` 一次，**只对「表里没有的目录」**解析 manifest。
 *
 * 第 3 步在稳态下**一次都不会进**：所有写入点（生成 / 导入 / 转正 / 删除）都同步落盘，磁盘与表
 * 天然一致。它只兜三种意外 —— 生成完 manifest 但还没落盘就被杀进程、从备份恢复了 filesDir、
 * 用户手工往目录里塞了快照。所以「每次冷启动解析全部清单」这件事不会再发生。
 *
 * 第 2 步刻意多 stat 一个 manifest 文件：转正（auto → 正式）时若进程正好被杀，表里会留着
 * `isAuto=true` 而磁盘上已经是 `manifest.json`。少了这一项，那条错行会一直留到下次全量扫描，
 * 点开时才发现读不到 `auto_manifest.json`。现在它会掉进第 3 步被重新解析、就地修正。
 *
 * ## 一致性
 *
 * 索引是缓存，真源永远是磁盘。写入点与删除点都主动回调本对象（[onSnapshotWritten] /
 * [onSnapshotPromoted] / [onSnapshotDeleted]），且都**同步落盘**。万一漏了一次，最坏后果是
 * 卡片上的标记晚一步出现或早一步消失（打开快照失败时 [ceui.pixiv.ui.common.IllustFeedFragment]
 * 会顺手摘掉那条过期索引），不会让任何操作指向不存在的目录。
 *
 * ## 补绑
 *
 * 卡片在 bind 时现读本索引，所以索引建好、或快照被增删时，已经绑好的卡片不会自己更新。
 * 消费方式一律是订阅 [revisionLive]，收到就发一次局部重绑 payload —— 与
 * [ceui.pixiv.ui.common.IllustMuteStore] 同一套做法。
 */
object SnapshotIndex {

    private const val TAG = "SnapshotIndex"

    /** 独立命名空间：与自动快照行为库、调试开关各用各的，互不影响清理。 */
    private const val MMKV_ID = "snapshot_index_v1"

    /** 整张表的 key。改结构要换 key（`table_v2`），不要就地改语义。 */
    private const val KEY_TABLE = "table_v1"

    /**
     * 一件作品的快照指向。
     *
     * @param isAuto 自动快照（`auto_manifest.json`）。点开时按它选 [AutoSnapshotRepository] 还是
     *   [SnapshotRepository] 回读，两者目录结构相同、只有 manifest 文件名不同。
     * @param createdAt 同一作品有多份快照时的取舍依据，见 [prefersOver]。
     */
    data class Entry(
        val snapshotId: String,
        val isAuto: Boolean,
        val createdAt: Long,
    )

    /**
     * 落盘形态。`illustId` 直接写进每条，而不是用 `Map<Long, Entry>`：Gson 会把 Long 键退化成
     * 字符串键，读回来还得再解析一次类型，不如数组直白。
     */
    private data class PersistedEntry(
        val illustId: Long = 0L,
        val snapshotId: String = "",
        val isAuto: Boolean = false,
        val createdAt: Long = 0L,
    )

    /**
     * 同一作品有多份快照时谁上卡片：**正式优先**（用户主动离线化过的东西比自动缓存更该被看见），
     * 同类型取新的。两份都能点开，选谁只影响「点进去是哪一份」。
     */
    private fun Entry.prefersOver(other: Entry): Boolean {
        if (isAuto != other.isAuto) return !isAuto
        return createdAt >= other.createdAt
    }

    /**
     * MMKV 实例。初始化失败降级为 null：本次进程不落盘（照常工作），冷启动退回全量扫描，
     * 绝不把异常抛给调用方 —— 同 [AutoSnapshotBehaviorStore]。
     */
    private val store: MMKV? by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }
            .onFailure { Timber.tag(TAG).e(it, "MMKV init failed, snapshot index will not persist") }
            .getOrNull()
    }

    /** 整份索引。整体替换而不是原地改：读方（主线程 bind）永远看到一份完整快照。 */
    @Volatile
    private var byIllust: Map<Long, Entry> = emptyMap()

    @Volatile
    private var loaded = false

    @Volatile
    private var loading = false

    private val revisionCounter = AtomicInteger()

    /**
     * 内存表被改过的次数。只给 [ensureLoaded] 用：扫描跑在 IO 上，扫的过程中可能已经有生成 /
     * 删除改了表（那份比扫描结果新），拿扫描结果覆盖回去会把刚写的条目吞掉。
     */
    private val mutationSeq = AtomicInteger()

    private val revisionLiveData = MutableLiveData(0)

    /** 版本号，只表示「变过了」，订阅方自己比对水位去重。 */
    val revision: Int
        get() = revisionCounter.get()

    /**
     * [revision] 的可观察版本。按 STARTED 投递，兄弟页（同一 pager 里同时 RESUMED 的 tab）
     * 也能当场收到 —— 与 [ceui.pixiv.ui.common.IllustMuteStore.revisionLive] 同理。
     */
    val revisionLive: LiveData<Int>
        get() = revisionLiveData

    /**
     * 这件作品的快照；没有（或索引还没建好）返回 null。
     *
     * **可能在主线程调用**，所以只读内存 Map，绝不在这里触发加载 —— 加载由 [ensureLoaded] 负责。
     * 索引没建好时先返回 null，建好后 [revisionLive] 会通知卡片补绑。
     */
    fun entryFor(illustId: Long): Entry? {
        if (illustId <= 0L) return null
        return byIllust[illustId]
    }

    /**
     * 幂等地保证索引已建好：读表 + 核对目录排在应用级后台 scope 上，绝不占调用线程。
     *
     * 两个调用点：[ceui.lisa.activities.Shaft] 启动预热（正常路径），以及插画列表页 / 浏览记录页
     * 建视图时的兜底（预热还没跑完就滑到首屏）。
     */
    fun ensureLoaded(context: Context) {
        if (loaded || loading) return
        synchronized(this) {
            if (loaded || loading) return
            loading = true
        }
        val appContext = context.applicationContext
        val seqAtStart = mutationSeq.get()
        JavaAsync.appScope.launch(Dispatchers.IO) {
            val table = runCatching { load(appContext) }
                .onFailure { Timber.tag(TAG).w(it, "加载快照索引失败，本次进程不显示卡片快照标记") }
                .getOrNull()
            synchronized(this@SnapshotIndex) {
                if (mutationSeq.get() == seqAtStart) {
                    byIllust = table?.first ?: emptyMap()
                    // 只在真的变过时回写：稳态冷启动（表与磁盘一致）一次磁盘写都不产生
                    if (table?.second == true) persistLocked()
                }
                // 否则：扫描期间已经有人改过表（生成 / 删除），内存里那份比扫描结果新，
                // 直接丢弃扫描结果 —— 它只是缓存，下次冷启动会自己对齐
                loaded = true
                loading = false
            }
            bumpRevision()
        }
    }

    /** 生成 / 导入成功后调用：这份快照立刻出现在卡片上，并且下次冷启动直接读表。 */
    fun onSnapshotWritten(illustId: Long, snapshotId: String, isAuto: Boolean) {
        if (illustId <= 0L || snapshotId.isEmpty()) return
        val entry = Entry(snapshotId, isAuto, System.currentTimeMillis())
        var changed = false
        synchronized(this) {
            val old = byIllust[illustId]
            // 已有更「该被看见」的一份（正式 > 自动、新的 > 旧的）就不覆盖：两份都能点开，
            // 卡片标记只看「有没有」，没必要为它把表改来改去
            if (old != null && old.snapshotId != snapshotId && !entry.prefersOver(old)) {
                return@synchronized
            }
            if (old == entry) return@synchronized
            byIllust = byIllust + (illustId to entry)
            mutationSeq.incrementAndGet()
            persistLocked()
            changed = true
        }
        if (changed) bumpRevision()
    }

    /** 自动快照转正：目录和 illustId 都没变，只是从自动来源变成了正式快照。 */
    fun onSnapshotPromoted(snapshotId: String) {
        if (snapshotId.isEmpty()) return
        var changed = false
        synchronized(this) {
            val key = byIllust.entries.firstOrNull { it.value.snapshotId == snapshotId }?.key
                ?: return@synchronized
            val old = byIllust[key] ?: return@synchronized
            if (!old.isAuto) return@synchronized
            byIllust = byIllust + (key to old.copy(isAuto = false))
            mutationSeq.incrementAndGet()
            persistLocked()
            changed = true
        }
        if (changed) bumpRevision()
    }

    /**
     * 快照目录没了（管理页删除 / 自动快照配额淘汰 / 导入覆盖失败 / 打开时发现 manifest 损坏）。
     * 反查一次 Map 即可，索引规模是「用户存过的快照数」，不值得再维护一张反向表。
     */
    fun onSnapshotDeleted(snapshotId: String) {
        if (snapshotId.isEmpty()) return
        var changed = false
        synchronized(this) {
            val next = byIllust.filterValues { it.snapshotId != snapshotId }
            if (next.size == byIllust.size) return@synchronized
            byIllust = next
            mutationSeq.incrementAndGet()
            persistLocked()
            changed = true
        }
        if (changed) bumpRevision()
    }

    private fun bumpRevision() {
        revisionLiveData.postValue(revisionCounter.incrementAndGet())
    }

    // ── 持久表（MMKV）─────────────────────────────────────────────────────────

    /** 读表。没有表 / 解析失败都返回空表，调用方会走一次全量扫描并重新落盘。 */
    private fun readTable(): Map<Long, Entry> {
        val s = store ?: return emptyMap()
        val raw = runCatching { s.decodeString(KEY_TABLE) }.getOrNull()
        if (raw.isNullOrEmpty()) return emptyMap()
        val rows = runCatching { Shaft.sGson.fromJson(raw, Array<PersistedEntry>::class.java) }
            .getOrNull() ?: return emptyMap()
        val out = HashMap<Long, Entry>(rows.size)
        // Gson 对数组里的 null 元素会原样塞进来，filterNotNull 一并挡掉
        for (row in rows.filterNotNull()) {
            if (row.illustId <= 0L || row.snapshotId.isEmpty()) continue
            val entry = Entry(row.snapshotId, row.isAuto, row.createdAt)
            val old = out[row.illustId]
            if (old == null || entry.prefersOver(old)) out[row.illustId] = entry
        }
        return out
    }

    /**
     * 整表回写。调用方必须持有本对象的锁（读的是 [byIllust]）。
     *
     * MMKV 的 encode 是 mmap 写、不 fsync，几十 KB 的表在调用线程上也就几十微秒，所以生成线程
     * （IO）和删除入口（主线程）都可以直接调，不必再往别处排。
     */
    private fun persistLocked() {
        val s = store ?: return
        val rows = byIllust.map { (illustId, e) ->
            PersistedEntry(illustId, e.snapshotId, e.isAuto, e.createdAt)
        }
        runCatching { s.encode(KEY_TABLE, Shaft.sGson.toJson(rows)) }
            .onFailure { Timber.tag(TAG).w(it, "写快照索引失败（下次冷启动会重新扫描）") }
    }

    // ── 冷启动建表 ─────────────────────────────────────────────────────────────

    /**
     * 建表。**不遍历清单**：表里每条只做两次 stat，只有「表里没有的目录」才解析 manifest ——
     * 稳态下那一支一次都不会进（见类注释）。
     *
     * @return 表 + 是否与落盘版本不同（不同才需要回写）。
     */
    private fun load(context: Context): Pair<Map<Long, Entry>, Boolean> {
        val persisted = readTable()
        val root = SnapshotRepository.root(context)
        val out = HashMap<Long, Entry>(persisted.size + 4)
        val known = HashSet<String>(persisted.size)
        var changed = false

        for ((illustId, entry) in persisted) {
            val dir = File(root, entry.snapshotId)
            val manifest = File(dir, if (entry.isAuto) AUTO_SNAPSHOT_MANIFEST else SNAPSHOT_MANIFEST)
            if (dir.isDirectory && manifest.isFile) {
                out[illustId] = entry
                known += entry.snapshotId
            } else {
                // 目录没了，或者转正时进程被杀导致 isAuto 与磁盘不符：不进 known，
                // 交给下面按目录重新解析一次，别让一条错行留到下次启动
                changed = true
            }
        }

        val dirs = root.listFiles()
        if (dirs != null) {
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                if (dir.name in known) continue
                val parsed = parseDir(dir) ?: continue
                val (illustId, entry) = parsed
                val old = out[illustId]
                if (old == null || entry.prefersOver(old)) {
                    out[illustId] = entry
                    changed = true
                }
            }
        }
        return out to changed
    }

    /**
     * 解析一个快照目录。口径与 [SnapshotRepository.list] / [AutoSnapshotRepository.listAuto] 一致：
     * 目录名必须等于 manifest 里的 `snapshotId`，有 `manifest.json` 的目录不再当作自动快照。
     * manifest 解析失败（手改坏 / 老版本格式）返回 null：宁可卡片上没有标记，也不要指一个打不开的目录。
     */
    private fun parseDir(dir: File): Pair<Long, Entry>? {
        val formal = SnapshotValidator.readJson<SnapshotManifest>(File(dir, SNAPSHOT_MANIFEST))
        val auto = if (formal == null) {
            SnapshotValidator.readJson<AutoSnapshotManifest>(File(dir, AUTO_SNAPSHOT_MANIFEST))
        } else {
            null
        }
        val snapshotId = formal?.snapshotId ?: auto?.snapshotId ?: return null
        if (dir.name != snapshotId) return null
        val illustId = formal?.illustId ?: auto?.illustId ?: return null
        if (illustId <= 0L) return null
        return illustId to Entry(
            snapshotId = snapshotId,
            isAuto = formal == null,
            createdAt = formal?.createdAt ?: auto?.createdAt ?: 0L,
        )
    }
}
