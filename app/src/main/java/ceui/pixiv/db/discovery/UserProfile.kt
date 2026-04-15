package ceui.pixiv.db.discovery

data class UserProfile(
    val tagScores: Map<String, Float>,
    val authorScores: Map<Long, Float>,
    val mutedTags: Set<String>,
    val mutedAuthors: Set<Long>,
    val avoidedTags: Set<String>,
    val seedIllusts: List<Long>,
    val seedAuthors: List<Long>,
    val avgBookmarkRate: Float,
    val builtTime: Long = System.currentTimeMillis()
) {
    /**
     * 画像是否足够完备，可以展示发现页。
     * 门槛：8+ 个偏好 tag、2+ 个种子作品、5+ 个强信号画师(分数>=3)
     * 降低门槛让更多用户更早享受个性化推荐
     */
    fun isReady(): Boolean {
        val strongAuthors = authorScores.count { it.value >= 3f }
        return tagScores.size >= 8 && seedIllusts.size >= 2 && strongAuthors >= 5
    }

    fun topTags(n: Int): List<Pair<String, Float>> {
        return tagScores.entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }
    }

    fun topAuthors(n: Int): List<Long> {
        return authorScores.entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }
    }
}
