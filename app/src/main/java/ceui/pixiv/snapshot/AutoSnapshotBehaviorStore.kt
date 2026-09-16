package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import com.tencent.mmkv.MMKV
import timber.log.Timber

/**
 * 自动快照行为信号存储。
 *
 * 专供「插画/漫画自动生成快照」使用，只记录本地浏览行为：
 * - 反复浏览：同作品在窗口内多次打开
 * - 长时间驻留：单次停留时长
 *
 * 存储选型：独立 MMKV 命名空间 + 每个作品一个 key。不与默认 MMKV / Settings /
 * 主数据库混用，文件级损坏也不会影响全局。
 *
 * 损坏自愈契约：任何 decode / parse / encode / allKeys 异常都不向上抛；单条记录损坏只删
 * 该条并从零开始；schema 不匹配或文件级异常则整库重置。调用方永远拿不到本层的异常。
 *
 * ⚠️ 选择性启用：本层不读取设置开关，由调用方（详情页 / AutoSnapshotEngine）在确认
 * 「插画/漫画自动生成快照」开启后才调用。开关关闭时不应写入任何行为记录。
 */
object AutoSnapshotBehaviorStore {

    private const val TAG = "AutoSnapshotBehavior"

    internal const val MMKV_ID = "auto_snapshot_behavior_v1"
    internal const val SCHEMA_VERSION = 1
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_PREFIX = "rec_"

    /** 行为统计窗口：7 天。 */
    internal const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** 单个作品最多保留的最近访问时间戳数量。 */
    internal const val MAX_RECENT_VISITS = 8

    /** 单个作品最多保留的最近停留样本数量。 */
    internal const val MAX_RECENT_DWELLS = 8

    /** 整个行为库最多保留的作品记录数，超出后删除最久未活跃的作品。 */
    internal const val MAX_RECORDS = 1000
    private const val PRUNE_INTERVAL_MS = 60L * 60 * 1000
    private var lastPruneAt: Long? = null

    const val SIGNAL_DWELL = "dwell"
    const val SIGNAL_REVISIT = "revisit"

    /**
     * MMKV 实例。独立命名空间；初始化失败时降级为 null，本次进程不再落盘，
     * 但绝不把异常抛给调用方。
     */
    private val store: MMKV? by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }
            .onFailure { Timber.tag(TAG).e(it, "MMKV init failed, behavior recording disabled for this process") }
            .getOrNull()
    }

    /** 记录一次打开（onResume / 页面真正可见时调用）。 */
    @Synchronized
    fun recordVisit(illustId: Long, type: String?, now: Long = System.currentTimeMillis()) {
        val s = store ?: return
        if (illustId <= 0L || !ensureSchema(s)) return
        val key = key(illustId)
        val old = load(s, key, illustId)
        val base = old ?: AutoSnapshotBehaviorRecord(illustId = illustId, type = type)
        val merged = (if (type != null) base.copy(type = type) else base).withVisit(now)
        save(s, key, merged, now)
    }

    /** 记录一次已完成的停留（onPause / 页面不可见时调用，dwellMs 为本次停留毫秒数）。 */
    @Synchronized
    fun recordDwell(illustId: Long, dwellMs: Long, now: Long = System.currentTimeMillis()) {
        val s = store ?: return
        if (illustId <= 0L || dwellMs <= 0L || !ensureSchema(s)) return
        val key = key(illustId)
        val old = load(s, key, illustId)
        val merged = (old ?: AutoSnapshotBehaviorRecord(illustId = illustId))
            .withDwell(dwellMs = dwellMs, now = now)
        save(s, key, merged, now)
    }

    /** 标记该作品已经生成过一次自动快照，供观察/节流/淘汰后续使用。 */
    @Synchronized
    fun markAutoSnapshotGenerated(
        illustId: Long,
        signal: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        val s = store ?: return
        if (illustId <= 0L || !ensureSchema(s)) return
        val key = key(illustId)
        val old = load(s, key, illustId)
        val merged = (old ?: AutoSnapshotBehaviorRecord(illustId = illustId))
            .withAutoSnapshot(now = now, signal = signal)
        save(s, key, merged, now)
    }

    /** 读取某个作品的记录；不存在或损坏时返回 null，不抛异常。 */
    @Synchronized
    fun read(illustId: Long): AutoSnapshotBehaviorRecord? {
        val s = store ?: return null
        if (illustId <= 0L || !ensureSchema(s)) return null
        return load(s, key(illustId), illustId)
    }

    /**
     * 清理过期与超量记录：
     * - 删除窗口内已无任何活跃信号的作品；
     * - 超过 [maxRecords] 时删除最久未活跃的作品。
     */
    @Synchronized
    fun prune(now: Long = System.currentTimeMillis(), maxRecords: Int = MAX_RECORDS) {
        val s = store ?: return
        if (!ensureSchema(s)) return
        val keys = runCatching { s.allKeys().orEmpty() }.getOrElse { return }
        val valid = mutableListOf<Pair<String, AutoSnapshotBehaviorRecord>>()
        for (key in keys) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val id = key.removePrefix(KEY_PREFIX).toLongOrNull()
            if (id == null) {
                runCatching { s.removeValueForKey(key) }
                continue
            }
            val record = load(s, key, id) ?: continue
            val newest = record.newestActivityAt()
            if (newest <= 0L || now - newest > WINDOW_MS) {
                runCatching { s.removeValueForKey(key) }
            } else {
                valid += key to record
            }
        }
        lastPruneAt = now
        val limit = maxRecords.coerceAtLeast(0)
        if (valid.size <= limit) return
        valid.sortByDescending { it.second.newestActivityAt() }
        for (i in limit until valid.size) {
            runCatching { s.removeValueForKey(valid[i].first) }
        }
    }

    /** 清空全部行为记录（调试 / 未来关闭开关时可选调用）。 */
    @Synchronized
    fun clearAll() {
        val s = store ?: return
        runCatching { s.clearAll() }
            .onFailure { Timber.tag(TAG).w(it, "clearAll failed") }
        ensureSchema(s)
    }

    private fun key(illustId: Long): String = KEY_PREFIX + illustId

    /** 校验 schema 版本；不匹配时整库重置并重新写入版本号。 */
    private fun ensureSchema(s: MMKV): Boolean {
        val current = runCatching { s.decodeInt(KEY_SCHEMA_VERSION, 0) }.getOrDefault(0)
        if (current == SCHEMA_VERSION) return true
        Timber.tag(TAG).w("schema mismatch current=%d expected=%d, resetting behavior store", current, SCHEMA_VERSION)
        runCatching { s.clearAll() }
            .onFailure {
                Timber.tag(TAG).e(it, "reset behavior store failed")
                return false
            }
        runCatching { s.encode(KEY_SCHEMA_VERSION, SCHEMA_VERSION) }
            .onFailure {
                Timber.tag(TAG).e(it, "write schema version failed")
                return false
            }
        return true
    }

    private fun load(s: MMKV, key: String, illustId: Long): AutoSnapshotBehaviorRecord? {
        val json = try {
            s.decodeString(key)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "read behavior record failed, resetting key=%s", key)
            runCatching { s.removeValueForKey(key) }
            return null
        } ?: return null
        val record = decodeRecord(illustId, json)
        if (record == null) {
            // 单条损坏：只删这一条，不影响其它作品，也不向上抛。
            runCatching { s.removeValueForKey(key) }
        }
        return record
    }

    private fun save(s: MMKV, key: String, record: AutoSnapshotBehaviorRecord, now: Long) {
        runCatching {
            if (!s.encode(key, encodeRecord(record))) return@runCatching
            // 配额属于行为库，不能依赖联网或成功生成快照。count() 不解析全部 JSON。
            // 超额时留出一批余量，避免达到上限后每翻一页就整库扫描。
            val previousPrune = lastPruneAt
            if (s.count() > MAX_RECORDS + 1L) {
                prune(now, maxRecords = MAX_RECORDS - 100)
            } else if (previousPrune == null || now < previousPrune || now - previousPrune >= PRUNE_INTERVAL_MS) {
                prune(now)
            }
        }
            .onFailure { Timber.tag(TAG).w(it, "write behavior record failed key=%s", key) }
    }

    /** 纯序列化入口，供单元测试直接验证。 */
    internal fun encodeRecord(record: AutoSnapshotBehaviorRecord): String =
        Shaft.sGson.toJson(record)

    /**
     * 纯反序列化入口，供单元测试直接验证。
     * 任何异常 / schema 不匹配 / id 不匹配都返回 null，不会抛给调用方。
     */
    internal fun decodeRecord(illustId: Long, json: String): AutoSnapshotBehaviorRecord? {
        return try {
            val record = Shaft.sGson.fromJson(json, AutoSnapshotBehaviorRecord::class.java)
            if (record.schemaVersion != SCHEMA_VERSION) return null
            if (record.illustId != illustId) return null
            if (record.illustId <= 0L) return null
            // Gson 的 Unsafe 分配会绕过 Kotlin 默认值/非空类型，缺字段、null 数组或
            // null 元素都可能解析成功。只向上层交付已经恢复非空不变式的有界记录。
            val visits: List<Long?>? = record.recentVisits
            val dwells: List<AutoSnapshotDwellSample?>? = record.recentDwells
            record.copy(
                recentVisits = visits.orEmpty().filterNotNull().take(MAX_RECENT_VISITS),
                recentDwells = dwells.orEmpty().filterNotNull()
                    .filter { it.ms > 0L }
                    .take(MAX_RECENT_DWELLS),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 单个作品的行为记录。
 *
 * 只存本地行为信号；不包含任何网络/账号数据。
 * [dwellAccumMs] 由 [recentDwells] 计算得出，不落盘。
 */
data class AutoSnapshotBehaviorRecord(
    val illustId: Long,
    val type: String? = null,
    val recentVisits: List<Long> = emptyList(),
    val visitCount: Int = 0,
    val lastDwellMs: Long = 0L,
    val recentDwells: List<AutoSnapshotDwellSample> = emptyList(),
    val lastAutoSnapshotAt: Long = 0L,
    val lastTriggerSignal: String? = null,
    val schemaVersion: Int = AutoSnapshotBehaviorStore.SCHEMA_VERSION,
) {

    /** 窗口内累计停留毫秒数（由 [recentDwells] 推导，Gson 不会持久化这个计算属性）。 */
    val dwellAccumMs: Long get() = recentDwells.sumOf { it.ms }

    /** 记录一次打开，并裁剪到窗口 + 上限。 */
    fun withVisit(now: Long, windowMs: Long = AutoSnapshotBehaviorStore.WINDOW_MS): AutoSnapshotBehaviorRecord {
        val recent = (recentVisits + now)
            .filter { now - it >= 0L && now - it <= windowMs }
            .sortedDescending()
            .take(AutoSnapshotBehaviorStore.MAX_RECENT_VISITS)
        return copy(
            recentVisits = recent,
            visitCount = visitCount + 1,
        )
    }

    /** 记录一次停留样本，并裁剪到窗口 + 上限。 */
    fun withDwell(
        dwellMs: Long,
        now: Long,
        windowMs: Long = AutoSnapshotBehaviorStore.WINDOW_MS,
    ): AutoSnapshotBehaviorRecord {
        val samples = (recentDwells + AutoSnapshotDwellSample(at = now, ms = dwellMs))
            .filter { now - it.at >= 0L && now - it.at <= windowMs }
            .sortedByDescending { it.at }
            .take(AutoSnapshotBehaviorStore.MAX_RECENT_DWELLS)
        return copy(
            recentDwells = samples,
            lastDwellMs = dwellMs,
            lastTriggerSignal = AutoSnapshotBehaviorStore.SIGNAL_DWELL,
        )
    }

    /** 标记已生成自动快照；可同时记录本次触发信号。 */
    fun withAutoSnapshot(now: Long, signal: String? = null): AutoSnapshotBehaviorRecord =
        copy(lastAutoSnapshotAt = now, lastTriggerSignal = signal ?: lastTriggerSignal)

    /** 按当前时间裁剪访问与停留样本。 */
    fun trimmed(now: Long, windowMs: Long = AutoSnapshotBehaviorStore.WINDOW_MS): AutoSnapshotBehaviorRecord = copy(
        recentVisits = recentVisits
            .filter { now - it >= 0L && now - it <= windowMs }
            .sortedDescending()
            .take(AutoSnapshotBehaviorStore.MAX_RECENT_VISITS),
        recentDwells = recentDwells
            .filter { now - it.at >= 0L && now - it.at <= windowMs }
            .sortedByDescending { it.at }
            .take(AutoSnapshotBehaviorStore.MAX_RECENT_DWELLS),
    )

    /** 最近一次活跃时间，用于淘汰排序；没有任何活跃信号时为 0。 */
    fun newestActivityAt(): Long = maxOf(
        recentVisits.maxOrNull() ?: 0L,
        recentDwells.maxOfOrNull { it.at } ?: 0L,
        lastAutoSnapshotAt,
    )
}

/** 一次已完成的停留样本：发生时间 + 毫秒数。 */
data class AutoSnapshotDwellSample(
    val at: Long,
    val ms: Long,
)
