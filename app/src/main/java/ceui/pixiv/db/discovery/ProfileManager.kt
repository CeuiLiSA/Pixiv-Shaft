package ceui.pixiv.db.discovery

import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import ceui.pixiv.db.RecordType
import ceui.pixiv.session.SessionManager
import timber.log.Timber
import kotlin.math.exp

object ProfileManager {

    private const val TAG = "Discovery/Profile"
    const val ACTION_PROFILE_READY = "ceui.pixiv.DISCOVERY_PROFILE_READY"
    private const val FOLLOW_WEIGHT = 4f

    @Volatile
    private var cachedProfile: UserProfile? = null

    fun cached(): UserProfile? = cachedProfile

    fun onFollowUser(userId: Long) {
        val profile = cachedProfile ?: return
        val newAuthorScores = profile.authorScores.toMutableMap()
        newAuthorScores[userId] = (newAuthorScores[userId] ?: 0f) + FOLLOW_WEIGHT
        cachedProfile = profile.copy(authorScores = newAuthorScores)
        Timber.d("$TAG onFollowUser userId=$userId, newScore=%.2f", newAuthorScores[userId])
    }

    fun onUnfollowUser(userId: Long) {
        val profile = cachedProfile ?: return
        val newAuthorScores = profile.authorScores.toMutableMap()
        newAuthorScores[userId] = ((newAuthorScores[userId] ?: 0f) - FOLLOW_WEIGHT).coerceAtLeast(0f)
        if (newAuthorScores[userId] == 0f) newAuthorScores.remove(userId)
        cachedProfile = profile.copy(authorScores = newAuthorScores)
        Timber.d("$TAG onUnfollowUser userId=$userId")
    }

    fun buildProfile(): UserProfile {
        val startTime = System.currentTimeMillis()
        Timber.d("$TAG buildProfile >>> started")

        val context = Shaft.getContext()
        val db = AppDatabase.getAppDatabase(context)
        val gson = Shaft.sGson

        // ====== 1. 读取本地数据源 ======
        val downloads = db.downloadDao().getAll(5000, 0)
        Timber.d("$TAG buildProfile downloads=${downloads.size}")

        val bookmarks = db.downloadDao().allFeatureEntities
        Timber.d("$TAG buildProfile bookmarks=${bookmarks.size}")

        val viewHistoryLegacy = db.downloadDao().allViewHistoryEntities
        Timber.d("$TAG buildProfile viewHistory(legacy)=${viewHistoryLegacy.size}")

        val viewHistoryNew = try {
            db.generalDao().getByRecordType(RecordType.VIEW_ILLUST_HISTORY, 0, 5000)
        } catch (e: Exception) {
            Timber.w(e, "$TAG buildProfile failed to read new view history")
            emptyList()
        }
        Timber.d("$TAG buildProfile viewHistory(new)=${viewHistoryNew.size}")

        val muteTags = db.searchDao().allMutedTags
        val muteUsers = db.searchDao().getMutedUser(1000, 0)
        Timber.d("$TAG buildProfile muteTags=${muteTags.size}, muteUsers=${muteUsers.size}")

        val blockedUserIds = try {
            db.generalDao().getAllIdsByRecordType(RecordType.BLOCK_USER)
        } catch (e: Exception) { emptyList() }

        // ====== 1.5 远程补充：收藏列表 + 关注列表 ======
        val remoteBookmarks = mutableListOf<IllustsBean>()
        val remoteFollowedAuthorIds = mutableListOf<Long>()
        val myUserId = SessionManager.loggedInUid.toInt()

        if (myUserId > 0) {
            try {
                Timber.d("$TAG buildProfile fetching remote bookmarks userId=$myUserId")
                val resp = Retro.getAppApi().getUserLikeIllust(myUserId, "public").blockingFirst()
                resp?.illusts?.let { remoteBookmarks.addAll(it) }
                Timber.d("$TAG buildProfile remote bookmarks=${remoteBookmarks.size}")
            } catch (e: Exception) {
                Timber.w(e, "$TAG buildProfile remote bookmarks failed")
            }

            try {
                Timber.d("$TAG buildProfile fetching remote following userId=$myUserId")
                val resp = Retro.getAppApi().getFollowUser(myUserId, "public").blockingFirst()
                resp?.list?.forEach { preview ->
                    val uid = preview.user?.id?.toLong() ?: return@forEach
                    if (uid > 0) remoteFollowedAuthorIds.add(uid)
                }
                Timber.d("$TAG buildProfile remote following=${remoteFollowedAuthorIds.size}")
            } catch (e: Exception) {
                Timber.w(e, "$TAG buildProfile remote following failed")
            }
        } else {
            Timber.d("$TAG buildProfile skip remote fetch: not logged in")
        }

        // 冷启动检测
        val totalBehavior = downloads.size + bookmarks.size + viewHistoryLegacy.size + viewHistoryNew.size + remoteBookmarks.size
        if (totalBehavior == 0) {
            Timber.d("$TAG buildProfile <<< COLD START: no data at all")
            cachedProfile = null
            broadcastReady()
            return UserProfile(
                tagScores = emptyMap(), authorScores = emptyMap(),
                mutedTags = emptySet(), mutedAuthors = emptySet(),
                avoidedTags = emptySet(), seedIllusts = emptyList(),
                seedAuthors = emptyList(), avgBookmarkRate = 0.05f
            )
        }

        // ====== 2. 反序列化，分正面池和全量池 ======
        val likedIllusts = mutableMapOf<Int, LikedRecord>()

        for (entity in downloads) {
            try {
                val illust = gson.fromJson(entity.illustGson, IllustsBean::class.java) ?: continue
                if (illust.id <= 0) continue
                val existing = likedIllusts[illust.id]
                if (existing != null) likedIllusts[illust.id] = existing.copy(weight = existing.weight + 5f)
                else likedIllusts[illust.id] = LikedRecord(illust, 5f, entity.downloadTime)
            } catch (e: Exception) {
                Timber.w(e, "$TAG buildProfile parse download failed: ${entity.fileName}")
            }
        }
        Timber.d("$TAG buildProfile parsed downloads -> liked=${likedIllusts.size}")

        var bookmarkParsed = 0
        for (entity in bookmarks) {
            try {
                val illust = gson.fromJson(entity.illustJson, IllustsBean::class.java) ?: continue
                if (illust.id <= 0) continue
                val existing = likedIllusts[illust.id]
                if (existing != null) likedIllusts[illust.id] = existing.copy(weight = existing.weight + 3f)
                else likedIllusts[illust.id] = LikedRecord(illust, 3f, entity.dateTime)
                bookmarkParsed++
            } catch (e: Exception) { }
        }
        Timber.d("$TAG buildProfile parsed local bookmarks=$bookmarkParsed")

        var remoteBookmarkParsed = 0
        for (illust in remoteBookmarks) {
            if (illust.id <= 0) continue
            val existing = likedIllusts[illust.id]
            if (existing != null) likedIllusts[illust.id] = existing.copy(weight = existing.weight + 3f)
            else likedIllusts[illust.id] = LikedRecord(illust, 3f, System.currentTimeMillis())
            remoteBookmarkParsed++
        }
        Timber.d("$TAG buildProfile parsed remote bookmarks=$remoteBookmarkParsed (liked total=${likedIllusts.size})")

        val viewedIllusts = mutableMapOf<Int, IllustsBean>()
        for (entity in viewHistoryLegacy) {
            try {
                val illust = gson.fromJson(entity.illustJson, IllustsBean::class.java) ?: continue
                if (illust.id > 0) viewedIllusts[illust.id] = illust
            } catch (_: Exception) {}
        }
        for (entity in viewHistoryNew) {
            try {
                val illust = gson.fromJson(entity.json, IllustsBean::class.java) ?: continue
                if (illust.id > 0) viewedIllusts[illust.id] = illust
            } catch (_: Exception) {}
        }
        for ((id, record) in likedIllusts) viewedIllusts.putIfAbsent(id, record.illust)
        Timber.d("$TAG buildProfile viewedPool=${viewedIllusts.size}")

        // ====== 3. 计算 tag lift ======
        val likedTagCount = mutableMapOf<String, Int>()
        val viewedTagCount = mutableMapOf<String, Int>()

        for ((_, record) in likedIllusts) {
            record.illust.tags?.forEach { tag ->
                val name = tag.name ?: return@forEach
                likedTagCount[name] = (likedTagCount[name] ?: 0) + 1
            }
        }
        for ((_, illust) in viewedIllusts) {
            illust.tags?.forEach { tag ->
                val name = tag.name ?: return@forEach
                viewedTagCount[name] = (viewedTagCount[name] ?: 0) + 1
            }
        }

        val totalLiked = likedIllusts.size.toFloat().coerceAtLeast(1f)
        val totalViewed = viewedIllusts.size.toFloat().coerceAtLeast(1f)
        val tagScores = mutableMapOf<String, Float>()
        val avoidedTags = mutableSetOf<String>()

        for ((tag, likedFreq) in likedTagCount) {
            val viewedFreq = viewedTagCount[tag] ?: 1
            val lift = (likedFreq / totalLiked) / (viewedFreq / totalViewed)
            // 至少喜欢过 2 次的标签才作为正向信号，减少低频噪声
            if (likedFreq >= 2 && lift > 1.5f) tagScores[tag] = lift
            else if (lift < 0.3f && viewedFreq >= 3) avoidedTags.add(tag)
        }
        for ((tag, viewedFreq) in viewedTagCount) {
            if (tag !in likedTagCount && viewedFreq >= 5) avoidedTags.add(tag)
        }

        Timber.d("$TAG buildProfile tagScores=${tagScores.size}, avoidedTags=${avoidedTags.size}")
        tagScores.entries.sortedByDescending { it.value }.take(10).forEach { (tag, lift) ->
            Timber.d("$TAG buildProfile   preferred tag='$tag' lift=%.2f", lift)
        }

        // ====== 4. 画师亲密度 ======
        val authorScores = mutableMapOf<Long, Float>()
        val now = System.currentTimeMillis()
        for ((_, record) in likedIllusts) {
            val userId = record.illust.user?.id?.toLong() ?: continue
            if (userId <= 0) continue
            val decay = recencyDecay(record.timestamp, now)
            authorScores[userId] = (authorScores[userId] ?: 0f) + record.weight * decay
        }
        for ((_, illust) in viewedIllusts) {
            val userId = illust.user?.id?.toLong() ?: continue
            if (userId <= 0 || userId in authorScores) continue
            authorScores[userId] = 1f
        }
        for (followedId in remoteFollowedAuthorIds) {
            authorScores[followedId] = (authorScores[followedId] ?: 0f) + FOLLOW_WEIGHT
        }
        val strongAuthors = authorScores.count { it.value >= 3f }
        Timber.d("$TAG buildProfile authorScores=${authorScores.size}, strongAuthors(>=3)=$strongAuthors")
        authorScores.entries.sortedByDescending { it.value }.take(5).forEach { (id, score) ->
            Timber.d("$TAG buildProfile   author id=$id score=%.2f", score)
        }

        // ====== 5. 种子作品（按 tag 分桶 + 兜底补充） ======
        val topTags = tagScores.entries.sortedByDescending { it.value }.take(20)
        val seedIllusts = mutableListOf<Long>()
        val usedIds = mutableSetOf<Int>()
        // 先按 top tag 分桶选
        for ((tagName, _) in topTags) {
            likedIllusts.values
                .filter { it.illust.id !in usedIds && it.illust.tags?.any { t -> t.name == tagName } == true }
                .maxByOrNull { if (it.illust.total_view > 0) it.illust.total_bookmarks.toFloat() / it.illust.total_view else 0f }
                ?.let {
                    seedIllusts.add(it.illust.id.toLong())
                    usedIds.add(it.illust.id)
                    Timber.d("$TAG buildProfile   seed illust id=${it.illust.id} tag='$tagName'")
                }
        }
        // 如果按 tag 分桶选不够 5 个，从 liked pool 按权重补充
        if (seedIllusts.size < 5) {
            likedIllusts.values
                .filter { it.illust.id !in usedIds }
                .sortedByDescending { it.weight }
                .take(5 - seedIllusts.size)
                .forEach {
                    seedIllusts.add(it.illust.id.toLong())
                    usedIds.add(it.illust.id)
                    Timber.d("$TAG buildProfile   seed illust (fallback) id=${it.illust.id} weight=${it.weight}")
                }
        }
        Timber.d("$TAG buildProfile seeds total=${seedIllusts.size}")

        // ====== 6. 种子画师 ======
        val seedAuthors = authorScores.entries.sortedByDescending { it.value }.take(10).map { it.key }

        // ====== 7. 平均收藏率 ======
        val rates = likedIllusts.values.map {
            if (it.illust.total_view > 0) it.illust.total_bookmarks.toFloat() / it.illust.total_view else 0f
        }
        val avgBookmarkRate = if (rates.isNotEmpty()) rates.average().toFloat() else 0.05f

        // ====== 8. 负面信号 ======
        val mutedTagNames = mutableSetOf<String>()
        for (entity in muteTags) {
            try {
                val tb = gson.fromJson(entity.tagJson, ceui.lisa.models.TagsBean::class.java)
                tb?.name?.let { mutedTagNames.add(it) }
            } catch (_: Exception) {}
        }
        val mutedAuthorIds = mutableSetOf<Long>()
        for (entity in muteUsers) mutedAuthorIds.add(entity.id.toLong())
        mutedAuthorIds.addAll(blockedUserIds)

        // ====== 9. 组装 ======
        val profile = UserProfile(
            tagScores = tagScores, authorScores = authorScores,
            mutedTags = mutedTagNames, mutedAuthors = mutedAuthorIds,
            avoidedTags = avoidedTags, seedIllusts = seedIllusts,
            seedAuthors = seedAuthors, avgBookmarkRate = avgBookmarkRate
        )
        cachedProfile = profile
        broadcastReady()
        // 画像重建后，用新画像重新给候选池打分
        DiscoveryPool.rescorePool()

        val elapsed = System.currentTimeMillis() - startTime
        Timber.d("$TAG buildProfile <<< DONE in ${elapsed}ms")
        Timber.d("$TAG buildProfile   data: downloads=${downloads.size}, localBookmarks=${bookmarks.size}, remoteBookmarks=${remoteBookmarks.size}, views=${viewHistoryLegacy.size + viewHistoryNew.size}")
        Timber.d("$TAG buildProfile   result: ${tagScores.size} tags, $strongAuthors strong authors, ${seedIllusts.size} seeds")
        Timber.d("$TAG buildProfile   isReady=${profile.isReady()} (tags=${tagScores.size}/15, seeds=${seedIllusts.size}/5, strongAuthors=$strongAuthors/10)")

        return profile
    }

    private fun broadcastReady() {
        try {
            LocalBroadcastManager.getInstance(Shaft.getContext())
                .sendBroadcast(Intent(ACTION_PROFILE_READY))
            Timber.d("$TAG broadcast ACTION_PROFILE_READY sent")
        } catch (e: Exception) {
            Timber.w(e, "$TAG broadcast failed")
        }
    }

    private fun recencyDecay(timestamp: Long, now: Long): Float {
        if (timestamp <= 0) return 0.5f
        val daysAgo = (now - timestamp) / 86_400_000.0
        return if (daysAgo <= 30) 1.0f
        else exp(-0.03 * (daysAgo - 30)).toFloat().coerceAtLeast(0.05f)
    }

    private data class LikedRecord(val illust: IllustsBean, val weight: Float, val timestamp: Long)
}
