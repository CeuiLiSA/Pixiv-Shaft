package ceui.pixiv.db.mirror

import androidx.room.InvalidationTracker
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.session.SessionManager
import java.util.concurrent.atomic.AtomicLong

/**
 * AI 屏蔽热路径使用的已关注作者快照。只有公开、私人两架都完成过首次回填，名单才可信。
 * Room 表变化时作废快照；切账号时按 uid 重新取，避免跨账号豁免。
 */
object AiFollowedAuthorExemption {
    private data class Snapshot(val ownerUid: Long, val revision: Long, val ids: Set<Long>)

    private val revision = AtomicLong()
    @Volatile private var snapshot: Snapshot? = null

    private val observer = object : InvalidationTracker.Observer(
        "bookmark_mirror_table", "bookmark_mirror_state_table"
    ) {
        override fun onInvalidated(tables: Set<String>) {
            revision.incrementAndGet()
            snapshot = null
        }
    }

    private val database by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).also {
            it.invalidationTracker.addObserver(observer)
        }
    }

    @JvmStatic
    fun isExempt(userId: Long): Boolean =
        userId > 0L && currentSnapshot()?.ids?.contains(userId) == true

    private fun currentSnapshot(): Snapshot? {
        if (!Shaft.sSettings.isAiBlockExemptFollowedAuthors ||
            !Shaft.sSettings.isBookmarkMirrorEnabled
        ) return null
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return null
        val version = revision.get()
        val current = snapshot
        if (current != null && current.ownerUid == uid && current.revision == version) return current
        return synchronized(this) {
            val freshVersion = revision.get()
            val cached = snapshot
            if (cached != null && cached.ownerUid == uid && cached.revision == freshVersion) {
                cached
            } else {
                val publicKey = BookmarkShelf(uid, MirrorContentType.USER, MirrorRestrict.PUBLIC).key
                val privateKey = BookmarkShelf(uid, MirrorContentType.USER, MirrorRestrict.PRIVATE).key
                val dao = database.bookmarkMirrorDao()
                val ready = dao.findState(publicKey)?.isFirstSyncDone == true &&
                    dao.findState(privateKey)?.isFirstSyncDone == true
                val ids = if (ready) dao.followedUserIds(publicKey, privateKey).toHashSet() else emptySet()
                Snapshot(uid, freshVersion, ids).also { snapshot = it }
            }
        }
    }
}