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
 * 卡片按作品 ID 查快照，只读不可变的内存 Map，绑定热路径不碰磁盘。
 *
 * 持久表按 snapshotId 保存所有候选，正式优先、同类型选创建时间最新的一份。
 * 删除首选后可立即退回另一份；冷启动只校验目录/manifest，未知目录才解析清单。
 * 表是可重建缓存，snapshot_index_v1 MMKV 命名空间在两套备份规则中均被排除。
 *
 * 首次扫描完成前的写入/删除按快照 ID 记录，合并时覆盖扫描中的旧记录。
 * 完整表就绪前不回写 MMKV，避免用尚未加载的部分数据覆盖持久表。
 */
object SnapshotIndex {
    private const val TAG = "SnapshotIndex"
    private const val MMKV_ID = "snapshot_index_v1"
    // v1 只保存各作品的首选，且 createdAt 可能是导入时间。换 key 后从磁盘重建一次。
    private const val KEY_TABLE = "table_v2"

    data class Entry(
        val snapshotId: String,
        val isAuto: Boolean,
        val createdAt: Long,
    )

    private data class PersistedEntry(
        val illustId: Long = 0L,
        val snapshotId: String? = null,
        val isAuto: Boolean = false,
        val createdAt: Long = 0L,
    )

    private val store: MMKV? by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }
            .onFailure { Timber.tag(TAG).e(it, "MMKV init failed, snapshot index will not persist") }
            .getOrNull()
    }

    private val state = SnapshotIndexState()

    @Volatile
    private var byIllust: Map<Long, Entry> = emptyMap()

    @Volatile
    private var loaded = false

    @Volatile
    private var loading = false

    private val revisionCounter = AtomicInteger()
    private val revisionLiveData = MutableLiveData(0)

    val revision: Int get() = revisionCounter.get()
    val revisionLive: LiveData<Int> get() = revisionLiveData

    fun entryFor(illustId: Long): Entry? = byIllust[illustId]

    /** 应用启动和列表建视图时调用；扫描由应用 scope 持有，不阻塞 UI。 */
    fun ensureLoaded(context: Context) {
        if (loaded || loading) return
        synchronized(this) {
            if (loaded || loading) return
            loading = true
        }
        val appContext = context.applicationContext
        JavaAsync.appScope.launch(Dispatchers.IO) {
            val table = runCatching {
                val persisted = readTable()
                val scanned = scanSnapshotIndex(SnapshotRepository.root(appContext), persisted)
                scanned to (scanned != persisted)
            }.onFailure {
                Timber.tag(TAG).w(it, "加载快照索引失败，下次建视图时重试")
            }.getOrNull()
            synchronized(this@SnapshotIndex) {
                if (table != null) {
                    val merged = state.finishLoading(table.first)
                    byIllust = state.preferredByIllust()
                    if (table.second || merged) persistLocked()
                    loaded = true
                }
                loading = false
            }
            bumpRevision()
        }
    }

    /** 生成、导入与转正都提供清单的真实创建时间，不能用本次操作时间替代。 */
    fun onSnapshotWritten(illustId: Long, snapshotId: String, isAuto: Boolean, createdAt: Long) {
        if (illustId <= 0L || snapshotId.isEmpty()) return
        synchronized(this) {
            val record = SnapshotIndexRecord(illustId, Entry(snapshotId, isAuto, createdAt))
            if (!state.write(record)) return
            byIllust = state.preferredByIllust()
            persistLocked()
        }
        bumpRevision()
    }

    /** 即使首轮扫描还未读到该 ID，也必须记录删除，防止旧扫描结果将它复活。 */
    fun onSnapshotDeleted(snapshotId: String) {
        if (snapshotId.isEmpty()) return
        val changed = synchronized(this) {
            val changed = state.delete(snapshotId)
            if (changed) {
                byIllust = state.preferredByIllust()
                persistLocked()
            }
            changed
        }
        if (changed) bumpRevision()
    }

    private fun bumpRevision() {
        revisionLiveData.postValue(revisionCounter.incrementAndGet())
    }

    private fun readTable(): Map<String, SnapshotIndexRecord> {
        val s = store ?: return emptyMap()
        val raw = runCatching { s.decodeString(KEY_TABLE) }.getOrNull()
        if (raw.isNullOrEmpty()) return emptyMap()
        val rows = runCatching { Shaft.sGson.fromJson(raw, Array<PersistedEntry?>::class.java) }
            .getOrNull() ?: return emptyMap()
        return buildMap {
            for (row in rows.filterNotNull()) {
                val id = row.snapshotId?.takeIf { it.isNotEmpty() } ?: continue
                if (row.illustId <= 0L || runCatching { requireSnapshotId(id) }.isFailure) continue
                put(id, SnapshotIndexRecord(row.illustId, Entry(id, row.isAuto, row.createdAt)))
            }
        }
    }

    /** 调用方持锁；首次加载未完成时保留原持久表，之后写完整的候选集合。 */
    private fun persistLocked() {
        if (!state.loaded) return
        val s = store ?: return
        val rows = state.records.values.map { (illustId, e) ->
            PersistedEntry(illustId, e.snapshotId, e.isAuto, e.createdAt)
        }
        runCatching { s.encode(KEY_TABLE, Shaft.sGson.toJson(rows)) }
            .onFailure { Timber.tag(TAG).w(it, "写快照索引失败（下次冷启动会重新扫描）") }
    }
}

internal data class SnapshotIndexRecord(val illustId: Long, val entry: SnapshotIndex.Entry)

/** 纯内存状态；生产调用由 SnapshotIndex 的锁串行化，读侧只接收 preferredByIllust 的快照。 */
internal class SnapshotIndexState {
    var records: Map<String, SnapshotIndexRecord> = emptyMap()
        private set
    var loaded = false
        private set
    private val pending = mutableMapOf<String, SnapshotIndexRecord?>()

    fun write(record: SnapshotIndexRecord): Boolean {
        val id = record.entry.snapshotId
        if (!loaded) pending[id] = record
        if (records[id] == record) return false
        records = records + (id to record)
        return true
    }

    fun delete(snapshotId: String): Boolean {
        if (!loaded) pending[snapshotId] = null
        if (snapshotId !in records) return false
        records = records - snapshotId
        return true
    }

    /** 合并扫描期间的变化；未触及的旧记录保留，删除/替换以最后一次操作为准。 */
    fun finishLoading(scanned: Map<String, SnapshotIndexRecord>): Boolean {
        val next = scanned.toMutableMap()
        pending.forEach { (id, record) ->
            if (record == null) next.remove(id) else next[id] = record
        }
        records = next
        pending.clear()
        loaded = true
        return next != scanned
    }

    fun preferredByIllust(): Map<Long, SnapshotIndex.Entry> = buildMap {
        for ((illustId, entry) in records.values) {
            val old = get(illustId)
            if (old == null || entry.prefersOver(old)) put(illustId, entry)
        }
    }
}

private fun SnapshotIndex.Entry.prefersOver(other: SnapshotIndex.Entry): Boolean = when {
    isAuto != other.isAuto -> !isAuto
    createdAt != other.createdAt -> createdAt > other.createdAt
    else -> snapshotId > other.snapshotId
}

/** 保留所有有效候选，重复作品也只在首次遇到目录时解析 manifest。 */
internal fun scanSnapshotIndex(
    root: File,
    persisted: Map<String, SnapshotIndexRecord>,
): Map<String, SnapshotIndexRecord> {
    val out = HashMap<String, SnapshotIndexRecord>(persisted.size + 4)
    for ((id, record) in persisted) {
        val dir = File(root, id)
        val formal = File(dir, SNAPSHOT_MANIFEST)
        val valid = if (record.entry.isAuto) {
            !formal.isFile && File(dir, AUTO_SNAPSHOT_MANIFEST).isFile
        } else {
            formal.isFile
        }
        if (dir.isDirectory && valid) out[id] = record
    }
    root.listFiles()?.forEach { dir ->
        if (!dir.isDirectory || dir.name in out) return@forEach
        val record = parseSnapshotIndexDir(dir) ?: return@forEach
        out[record.entry.snapshotId] = record
    }
    return out
}

private fun parseSnapshotIndexDir(dir: File): SnapshotIndexRecord? {
    // 正式 manifest 存在就不再按自动快照解释，与两个仓库的列表口径一致。
    val formalFile = File(dir, SNAPSHOT_MANIFEST)
    val formal = if (formalFile.isFile) {
        SnapshotValidator.readJson<SnapshotManifest>(formalFile) ?: return null
    } else null
    val auto = if (formal == null) {
        SnapshotValidator.readJson<AutoSnapshotManifest>(File(dir, AUTO_SNAPSHOT_MANIFEST)) ?: return null
    } else null
    val snapshotId = formal?.snapshotId ?: auto?.snapshotId ?: return null
    if (dir.name != snapshotId) return null
    val illustId = formal?.illustId ?: auto?.illustId ?: return null
    if (illustId <= 0L) return null
    return SnapshotIndexRecord(
        illustId,
        SnapshotIndex.Entry(snapshotId, formal == null, formal?.createdAt ?: auto?.createdAt ?: 0L),
    )
}
