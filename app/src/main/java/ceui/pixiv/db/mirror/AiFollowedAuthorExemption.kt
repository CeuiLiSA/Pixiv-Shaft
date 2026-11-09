package ceui.pixiv.db.mirror

import androidx.room.InvalidationTracker
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.session.SessionManager
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * AI 屏蔽热路径使用的已关注作者快照。只有公开、私人两架都完成过首次回填，名单才可信。
 * Room 表变化后在查询执行器上刷新；切账号时按 uid 重新取，避免跨账号豁免。
 */
object AiFollowedAuthorExemption {
    private val snapshots by lazy {
        val database = AppDatabase.getAppDatabase(Shaft.getContext())
        val cache = FollowedAuthorSnapshotCache(database.queryExecutor) { uid ->
            val publicKey = BookmarkShelf(uid, MirrorContentType.USER, MirrorRestrict.PUBLIC).key
            val privateKey = BookmarkShelf(uid, MirrorContentType.USER, MirrorRestrict.PRIVATE).key
            database.bookmarkMirrorDao().followedUserIds(publicKey, privateKey)
        }
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            "bookmark_mirror_table", "bookmark_mirror_state_table"
        ) {
            override fun onInvalidated(tables: Set<String>) = cache.invalidate()
        })
        cache
    }

    @JvmStatic
    fun isExempt(userId: Long): Boolean = isExempt(userId, allowStale = true)

    /** 一次性过滤不能等后台刷新后再纠正，必须使用本次失效之后的快照。 */
    @JvmStatic
    fun isExemptForFiltering(userId: Long): Boolean = isExempt(userId, allowStale = false)

    private fun isExempt(userId: Long, allowStale: Boolean): Boolean {
        if (userId <= 0L || !Shaft.sSettings.isAiBlockExemptFollowedAuthors ||
            !Shaft.sSettings.isBookmarkMirrorEnabled
        ) return false
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return false
        return if (allowStale) snapshots.containsCached(uid, userId) else snapshots.contains(uid, userId)
    }
}

/**
 * [containsCached] 给 bind 只读已有内存快照；表级通知在后台合并刷新。
 * [contains] 给一次性过滤：失效后先加载最新快照，避免按旧名单剔掉条目后无法恢复。
 * 首次/切号保留同步加载兜底（同 MutedWorkStore），避免首批过滤漏掉豁免；慢查询不阻塞同账号读热路径。
 */
internal class FollowedAuthorSnapshotCache(
    private val executor: Executor,
    private val loadIds: (Long) -> Collection<Long>,
) {
    private data class Snapshot(val ownerUid: Long, val revision: Long, val ids: Set<Long>)

    private val revision = AtomicLong()
    private val refreshScheduled = AtomicBoolean()
    @Volatile private var snapshot: Snapshot? = null

    fun contains(ownerUid: Long, userId: Long): Boolean = contains(ownerUid, userId, allowStale = false)

    fun containsCached(ownerUid: Long, userId: Long): Boolean = contains(ownerUid, userId, allowStale = true)

    private fun contains(ownerUid: Long, userId: Long, allowStale: Boolean): Boolean {
        if (ownerUid <= 0L || userId <= 0L) return false
        val current = snapshot
        val loaded = if (current != null && current.ownerUid == ownerUid &&
            (allowStale || current.revision == revision.get())
        ) current else {
            synchronized(this) {
                snapshot?.takeIf { it.ownerUid == ownerUid && (allowStale || it.revision == revision.get()) }
                    ?: readSnapshot(ownerUid).also { snapshot = it }
            }
        }
        if (loaded.revision != revision.get()) refreshAsync()
        return userId in loaded.ids
    }

    fun invalidate() {
        revision.incrementAndGet()
        refreshAsync()
    }

    private fun readSnapshot(ownerUid: Long): Snapshot {
        val version = revision.get()
        return Snapshot(ownerUid, version, loadIds(ownerUid).toHashSet())
    }

    private fun refreshAsync() {
        if (snapshot == null || !refreshScheduled.compareAndSet(false, true)) return
        executor.execute {
            var refreshed = false
            try {
                synchronized(this) {
                    val current = snapshot
                    if (current != null && current.revision != revision.get()) {
                        snapshot = readSnapshot(current.ownerUid)
                    }
                }
                refreshed = true
            } catch (e: Exception) {
                Timber.w(e, "刷新 AI 豁免关注作者快照失败")
            } finally {
                refreshScheduled.set(false)
            }
            // 查询期间收到的新通知不能被本次发布盖掉；失败则等下一次读取/变更再重试。
            if (refreshed && snapshot?.revision != revision.get()) refreshAsync()
        }
    }
}
