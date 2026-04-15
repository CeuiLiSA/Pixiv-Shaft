package ceui.pixiv.db.discovery

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.IllustsBean
import ceui.pixiv.db.DiscoveryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.LinkedList
import java.util.Random
import kotlin.math.ln
import kotlin.math.pow

object DiscoveryPool {

    private const val TAG = "Discovery/Pool"
    private const val MAX_POOL_SIZE = 2000
    private const val MAX_RECENT_IDS = 800

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pooledIds = mutableSetOf<Long>()
    private val seenIds = mutableSetOf<Long>()
    private var initialized = false
    private var rescoreJob: Job? = null

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

                    val userId = illust.user?.id?.toLong() ?: 0

                    if (profile != null) {
                        if (userId in profile.mutedAuthors) { skipMutedAuthor++; continue }
                        val tags = illust.tags?.mapNotNull { it.name } ?: emptyList()
                        if (tags.any { it in profile.mutedTags }) { skipMutedTag++; continue }
                        if (tags.count { it in profile.avoidedTags } >= 2) { skipAvoided++; continue }

                        // 计算亲和度：tag/author 高匹配的内容不受 quality filter 限制
                        val affinity = quickAffinity(illust, profile)
                        val views = illust.total_view
                        // 只对低亲和度内容执行质量过滤
                        if (affinity < 2f) {
                            val qualityThreshold = minOf(profile.avgBookmarkRate * 0.2f, 0.015f)
                            if (views > 100 && illust.total_bookmarks.toFloat() / views < qualityThreshold) {
                                skipLowQuality++; continue
                            }
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

                    entities.add(DiscoveryEntity(illustId, gson.toJson(illust), score, source, authorId = userId))
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

    /**
     * 快速计算亲和度，用于决定是否跳过 quality filter。
     * 不需要完整的 scoreDetailed，只看 tag 和 author 强信号。
     */
    private fun quickAffinity(illust: IllustsBean, profile: UserProfile): Float {
        var aff = 0f
        illust.tags?.forEach { tag ->
            val name = tag.name ?: return@forEach
            profile.tagScores[name]?.let { if (it > 2f) aff += 1f }
        }
        val userId = illust.user?.id?.toLong() ?: 0
        profile.authorScores[userId]?.let { if (it >= 3f) aff += 2f }
        return aff
    }

    private data class ScoreBreakdown(val total: Float, val detail: String)

    private fun scoreDetailed(illust: IllustsBean, profile: UserProfile): ScoreBreakdown {
        var tagScore = 0f; var matched = 0
        val matchedLifts = mutableListOf<Float>()
        illust.tags?.forEach { tag ->
            val name = tag.name ?: return@forEach
            profile.tagScores[name]?.let {
                tagScore += it; matched++
                matchedLifts.add(it)
            }
        }
        // 归一化：matched^0.4 替代 sqrt，减少对多标签匹配作品的惩罚
        if (matched > 1) tagScore /= matched.toDouble().pow(0.4).toFloat()
        // 标签协同加分：2+ 个偏好标签(lift>1.8)同时命中，说明口味高度吻合
        val highLiftCount = matchedLifts.count { it > 1.8f }
        val synergyBonus = if (highLiftCount >= 2) (highLiftCount - 1) * 1.2f else 0f

        // 画师亲和度：smoothstep 平滑过渡，消除 score=3 处的断崖
        var authorScore = 0f
        val authorRaw = profile.authorScores[illust.user?.id?.toLong() ?: 0]
        if (authorRaw != null) {
            val t = ((authorRaw - 1f) / 3f).coerceIn(0f, 1f)
            val multiplier = 0.3f + 0.7f * t * t * (3f - 2f * t)
            authorScore = authorRaw * multiplier
        }

        // 质量分：bookmark rate 对数缩放，避免极端值主导
        var qualityScore = 0f
        if (illust.total_view > 100) {
            val rate = illust.total_bookmarks.toFloat() / illust.total_view
            qualityScore = (ln((rate * 100).toDouble().coerceAtLeast(1.0)) * 3f).toFloat()
        }

        // 新鲜度：连续对数曲线替代阶梯函数，消除 500/2000/5000 处的跳变
        var freshScore = 0f
        if (illust.total_bookmarks > 5) {
            val views = illust.total_view.toFloat().coerceAtLeast(1f)
            freshScore = (3f * (1f - ln(views.toDouble().coerceAtLeast(1.0)) / ln(5000.0)))
                .toFloat().coerceIn(0f, 3f)
        }

        val total = tagScore + synergyBonus + authorScore + qualityScore + freshScore
        return ScoreBreakdown(total, "tag=%.1f(%d) syn=%.1f author=%.1f quality=%.1f fresh=%.1f".format(
            tagScore, matched, synergyBonus, authorScore, qualityScore, freshScore))
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

    // 使用有界 LinkedList 替代 mutableSet + 全量清除，避免 cliff-drop 重复
    private val recentLock = Any()
    private val recentlyReturnedIds = LinkedList<Long>()
    private val recentlyReturnedSet = mutableSetOf<Long>()
    private val feedRandom = Random()

    private fun addRecentId(id: Long) {
        synchronized(recentLock) {
            if (recentlyReturnedSet.add(id)) {
                recentlyReturnedIds.addLast(id)
                while (recentlyReturnedIds.size > MAX_RECENT_IDS) {
                    val evicted = recentlyReturnedIds.removeFirst()
                    recentlyReturnedSet.remove(evicted)
                }
            }
        }
    }

    /**
     * 多样化推荐 feed：从候选池加权随机采样 + 画师去重，
     * 打破纯 score DESC 的信息茧房。
     */
    fun getDiscoveryFeedDiversified(limit: Int = 50): List<DiscoveryEntity> {
        return try {
            val candidateSize = limit * 4
            val dao = AppDatabase.getAppDatabase(Shaft.getContext()).discoveryDao()
            val snapshot = synchronized(recentLock) { recentlyReturnedSet.toSet() }
            val candidates = dao.getUnshown(candidateSize, 0)
                .filter { it.illustId !in snapshot }

            if (candidates.isEmpty()) {
                Timber.d("$TAG diversifiedFeed: no candidates")
                return emptyList()
            }
            if (candidates.size <= limit) {
                Timber.d("$TAG diversifiedFeed: only ${candidates.size} candidates, returning all")
                candidates.forEach { addRecentId(it.illustId) }
                return candidates
            }

            val result = weightedDiverseSample(candidates, limit)
            result.forEach { addRecentId(it.illustId) }

            Timber.d("$TAG diversifiedFeed: sampled ${result.size} from ${candidates.size} candidates")
            result
        } catch (e: Exception) {
            Timber.e(e, "$TAG diversifiedFeed failed"); emptyList()
        }
    }

    private fun weightedDiverseSample(candidates: List<DiscoveryEntity>, limit: Int): List<DiscoveryEntity> {
        val result = mutableListOf<DiscoveryEntity>()
        val authorCount = mutableMapOf<Long, Int>()
        val remaining = candidates.toMutableList()
        val maxPerAuthor = 2
        var retries = 0
        val maxRetries = limit * 2

        while (result.size < limit && remaining.isNotEmpty() && retries < maxRetries) {
            // score^0.85 适度软化：高分作品保持优势，中低分也有机会
            val weights = remaining.map { it.score.coerceAtLeast(0.1f).toDouble().pow(0.85) }
            val totalWeight = weights.sum()
            if (totalWeight <= 0) break

            var r = feedRandom.nextDouble() * totalWeight
            var pickedIdx = remaining.size - 1
            for (i in remaining.indices) {
                r -= weights[i]
                if (r <= 0) { pickedIdx = i; break }
            }
            val picked = remaining.removeAt(pickedIdx)

            // 直接使用存储的 authorId，不再反序列化 JSON
            val authorId = picked.authorId

            if (authorId > 0 && (authorCount[authorId] ?: 0) >= maxPerAuthor) {
                retries++
                continue
            }
            if (authorId > 0) authorCount[authorId] = (authorCount[authorId] ?: 0) + 1
            result.add(picked)
        }

        return result
    }

    /**
     * 对池中未展示的作品重新打分。
     * @param debounceMs 防抖延迟。收藏等高频操作传 >0 值，避免连续收藏时重复全量打分；
     *                   buildProfile 等一次性调用传 0 立即执行。
     */
    fun rescorePool(debounceMs: Long = 0L) {
        rescoreJob?.cancel()
        rescoreJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            try {
                val profile = ProfileManager.cached() ?: return@launch
                val db = AppDatabase.getAppDatabase(Shaft.getContext())
                val dao = db.discoveryDao()
                val gson = Shaft.sGson
                val unshown = dao.getAllUnshown()
                val pendingUpdates = mutableListOf<Pair<Long, Float>>()

                for (entity in unshown) {
                    try {
                        val illust = gson.fromJson(entity.illustJson, IllustsBean::class.java) ?: continue
                        val newScore = scoreDetailed(illust, profile).total
                        if (kotlin.math.abs(newScore - entity.score) > 0.5f) {
                            pendingUpdates.add(entity.illustId to newScore)
                        }
                    } catch (_: Exception) {}
                }

                if (pendingUpdates.isNotEmpty()) {
                    db.runInTransaction {
                        for ((id, score) in pendingUpdates) {
                            dao.updateScore(id, score)
                        }
                    }
                }
                Timber.d("$TAG rescorePool: checked=${unshown.size}, updated=${pendingUpdates.size}")
            } catch (e: Exception) {
                Timber.e(e, "$TAG rescorePool failed")
            }
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
