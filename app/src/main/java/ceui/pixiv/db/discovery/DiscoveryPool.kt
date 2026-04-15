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
import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt

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
                        // 阈值加上限 1.5%，保护小众优质内容不被误杀
                        val qualityThreshold = minOf(profile.avgBookmarkRate * 0.2f, 0.015f)
                        if (views > 100 && illust.total_bookmarks.toFloat() / views < qualityThreshold) {
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
        // 归一化：除以 sqrt(匹配数)，避免标签多的作品天然得分过高
        if (matched > 1) tagScore /= sqrt(matched.toFloat())

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

    private val recentlyReturnedIds = mutableSetOf<Long>()
    private val feedRandom = Random()

    /**
     * 多样化推荐 feed：从候选池加权随机采样 + 画师去重，
     * 打破纯 score DESC 的信息茧房。
     */
    fun getDiscoveryFeedDiversified(limit: Int = 50): List<DiscoveryEntity> {
        return try {
            val candidateSize = limit * 4
            val dao = AppDatabase.getAppDatabase(Shaft.getContext()).discoveryDao()
            val candidates = dao.getUnshown(candidateSize, 0)
                .filter { it.illustId !in recentlyReturnedIds }

            if (candidates.isEmpty()) {
                Timber.d("$TAG diversifiedFeed: no candidates")
                return emptyList()
            }
            if (candidates.size <= limit) {
                Timber.d("$TAG diversifiedFeed: only ${candidates.size} candidates, returning all")
                candidates.forEach { recentlyReturnedIds.add(it.illustId) }
                return candidates
            }

            val result = weightedDiverseSample(candidates, limit)
            result.forEach { recentlyReturnedIds.add(it.illustId) }
            if (recentlyReturnedIds.size > MAX_POOL_SIZE) recentlyReturnedIds.clear()

            Timber.d("$TAG diversifiedFeed: sampled ${result.size} from ${candidates.size} candidates")
            result
        } catch (e: Exception) {
            Timber.e(e, "$TAG diversifiedFeed failed"); emptyList()
        }
    }

    private fun weightedDiverseSample(candidates: List<DiscoveryEntity>, limit: Int): List<DiscoveryEntity> {
        val gson = Shaft.sGson
        val result = mutableListOf<DiscoveryEntity>()
        val authorCount = mutableMapOf<Long, Int>()
        val remaining = candidates.toMutableList()
        val maxPerAuthor = 3
        var retries = 0
        val maxRetries = limit * 2

        while (result.size < limit && remaining.isNotEmpty() && retries < maxRetries) {
            // score^0.7 软化分布，让中低分作品也有出现机会
            val weights = remaining.map { it.score.coerceAtLeast(0.1f).toDouble().pow(0.7) }
            val totalWeight = weights.sum()
            if (totalWeight <= 0) break

            var r = feedRandom.nextDouble() * totalWeight
            var pickedIdx = remaining.size - 1
            for (i in remaining.indices) {
                r -= weights[i]
                if (r <= 0) { pickedIdx = i; break }
            }
            val picked = remaining.removeAt(pickedIdx)

            // 画师多样性：同一画师最多出现 maxPerAuthor 次
            val authorId = try {
                gson.fromJson(picked.illustJson, IllustsBean::class.java)?.user?.id?.toLong() ?: 0L
            } catch (_: Exception) { 0L }

            if (authorId > 0 && (authorCount[authorId] ?: 0) >= maxPerAuthor) {
                retries++
                continue
            }
            if (authorId > 0) authorCount[authorId] = (authorCount[authorId] ?: 0) + 1
            result.add(picked)
        }

        return result
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
