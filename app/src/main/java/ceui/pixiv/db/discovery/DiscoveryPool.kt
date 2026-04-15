package ceui.pixiv.db.discovery

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.IllustsBean
import ceui.pixiv.db.DiscoveryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

object DiscoveryPool {

    private const val TAG = "Discovery/Pool"
    private const val MAX_POOL_SIZE = 2000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pooledIds = mutableSetOf<Long>()
    private val seenIds = mutableSetOf<Long>()
    private var initialized = false

    fun initialize() {
        Timber.d("$TAG initialize called")
        scope.launch {
            val t = System.currentTimeMillis()
            try {
                val db = AppDatabase.getAppDatabase(Shaft.getContext())
                val dao = db.discoveryDao()

                dao.getUnshown(MAX_POOL_SIZE, 0).forEach { pooledIds.add(it.illustId) }
                val total = dao.count()
                val unshown = dao.countUnshown()
                Timber.d("$TAG initialize pooledIds=${pooledIds.size}, db(total=$total, unshown=$unshown)")

                val legacyIds = db.downloadDao().allViewHistoryEntities.map { it.illustID.toLong() }
                seenIds.addAll(legacyIds)
                try {
                    seenIds.addAll(db.generalDao().getAllIdsByRecordType(ceui.pixiv.db.RecordType.VIEW_ILLUST_HISTORY))
                } catch (_: Exception) {}

                initialized = true
                Timber.d("$TAG initialize done in ${System.currentTimeMillis() - t}ms, seenIds=${seenIds.size}")
            } catch (e: Exception) {
                Timber.e(e, "$TAG initialize failed")
            }
        }
    }

    fun collect(illusts: List<IllustsBean>?, source: String) {
        if (illusts.isNullOrEmpty()) {
            Timber.d("$TAG collect [%s] empty input, skip", source)
            return
        }
        Timber.d("$TAG collect [%s] >>> %d illusts, initialized=%s", source, illusts.size, initialized)

        scope.launch {
            try {
                val profile = ProfileManager.cached()
                val coldStart = profile == null
                if (coldStart) {
                    Timber.d("$TAG collect [%s] COLD START mode", source)
                } else {
                    Timber.d("$TAG collect [%s] PROFILE mode: tags=%d authors=%d muted=%d/%d avoided=%d",
                        source, profile!!.tagScores.size, profile.authorScores.size,
                        profile.mutedTags.size, profile.mutedAuthors.size, profile.avoidedTags.size)
                }

                val dao = AppDatabase.getAppDatabase(Shaft.getContext()).discoveryDao()
                val gson = Shaft.sGson
                var accepted = 0
                var skipInvalid = 0; var skipSeen = 0; var skipPooled = 0
                var skipBookmarked = 0; var skipMutedAuthor = 0; var skipMutedTag = 0
                var skipAvoided = 0; var skipLowQuality = 0
                val entities = mutableListOf<DiscoveryEntity>()

                for (illust in illusts) {
                    val illustId = illust.id.toLong()
                    if (illustId <= 0) { skipInvalid++; continue }
                    if (illustId in seenIds) { skipSeen++; continue }
                    if (illustId in pooledIds) { skipPooled++; continue }
                    if (illust.isIs_bookmarked) { skipBookmarked++; continue }

                    if (profile != null) {
                        val userId = illust.user?.id?.toLong() ?: 0
                        if (userId in profile.mutedAuthors) { skipMutedAuthor++; continue }
                        val tags = illust.tags?.mapNotNull { it.name } ?: emptyList()
                        if (tags.any { it in profile.mutedTags }) { skipMutedTag++; continue }
                        if (tags.count { it in profile.avoidedTags } >= 2) { skipAvoided++; continue }
                        val views = illust.total_view
                        if (views > 100 && illust.total_bookmarks.toFloat() / views < profile.avgBookmarkRate * 0.2f) {
                            skipLowQuality++; continue
                        }
                    }

                    val score: Float
                    val detail: String
                    if (profile != null) {
                        val b = scoreDetailed(illust, profile)
                        score = b.total; detail = b.detail
                    } else {
                        score = scoreColdStart(illust)
                        detail = "cold(v=${illust.total_view},b=${illust.total_bookmarks})"
                    }

                    Timber.d("$TAG collect [%s] + id=%d '%s' by '%s' score=%.2f [%s]",
                        source, illustId, illust.title ?: "?", illust.user?.name ?: "?", score, detail)

                    entities.add(DiscoveryEntity(illustId, gson.toJson(illust), score, source))
                    pooledIds.add(illustId)
                    accepted++
                }

                if (entities.isNotEmpty()) dao.insertAll(entities)
                val curSize = dao.count()
                if (curSize > MAX_POOL_SIZE) {
                    dao.trimToSize(MAX_POOL_SIZE)
                    Timber.d("$TAG collect [%s] TRIM %d -> %d", source, curSize, MAX_POOL_SIZE)
                }

                Timber.d("$TAG collect [%s] <<< accepted=%d skip(invalid=%d seen=%d pooled=%d bookmarked=%d mutedAuthor=%d mutedTag=%d avoided=%d lowQ=%d) pool(total=%d unshown=%d)",
                    source, accepted, skipInvalid, skipSeen, skipPooled, skipBookmarked,
                    skipMutedAuthor, skipMutedTag, skipAvoided, skipLowQuality, dao.count(), dao.countUnshown())
            } catch (e: Exception) {
                Timber.e(e, "$TAG collect [%s] EXCEPTION", source)
            }
        }
    }

    private data class ScoreBreakdown(val total: Float, val detail: String)

    private fun scoreDetailed(illust: IllustsBean, profile: UserProfile): ScoreBreakdown {
        var tagScore = 0f; var matched = 0
        illust.tags?.forEach { tag ->
            val name = tag.name ?: return@forEach
            profile.tagScores[name]?.let { tagScore += it; matched++ }
        }
        var authorScore = 0f
        profile.authorScores[illust.user?.id?.toLong() ?: 0]?.let { authorScore = it * 0.5f }
        var qualityScore = 0f
        if (illust.total_view > 100) qualityScore = illust.total_bookmarks.toFloat() / illust.total_view * 20f
        var freshScore = 0f
        if (illust.total_view < 1000 && illust.total_bookmarks > 10) freshScore = 2f
        val total = tagScore + authorScore + qualityScore + freshScore
        return ScoreBreakdown(total, "tag=%.1f(%d) author=%.1f quality=%.1f fresh=%.1f".format(
            tagScore, matched, authorScore, qualityScore, freshScore))
    }

    private fun scoreColdStart(illust: IllustsBean): Float {
        var s = 0f
        if (illust.total_view > 0) s += illust.total_bookmarks.toFloat() / illust.total_view * 100f
        if (illust.total_bookmarks > 100) s += 5f
        return s
    }

    fun getDiscoveryFeed(limit: Int = 50, offset: Int = 0): List<DiscoveryEntity> {
        return try {
            val r = AppDatabase.getAppDatabase(Shaft.getContext()).discoveryDao().getUnshown(limit, offset)
            Timber.d("$TAG getDiscoveryFeed limit=$limit offset=$offset -> ${r.size}")
            r
        } catch (e: Exception) {
            Timber.e(e, "$TAG getDiscoveryFeed failed"); emptyList()
        }
    }

    fun markShown(illustId: Long) {
        scope.launch {
            try {
                AppDatabase.getAppDatabase(Shaft.getContext()).discoveryDao().markShown(illustId)
                Timber.d("$TAG markShown $illustId")
            } catch (e: Exception) {
                Timber.e(e, "$TAG markShown failed $illustId")
            }
        }
    }

    fun getStats(): String {
        return try {
            val dao = AppDatabase.getAppDatabase(Shaft.getContext()).discoveryDao()
            "pool(total=${dao.count()}, unshown=${dao.countUnshown()}), mem(pooled=${pooledIds.size}, seen=${seenIds.size}), init=$initialized"
        } catch (e: Exception) { "error: ${e.message}" }
    }
}
