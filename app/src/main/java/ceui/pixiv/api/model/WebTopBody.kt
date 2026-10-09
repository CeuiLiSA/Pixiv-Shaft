package ceui.pixiv.api.model

import java.io.Serializable

/**
 * 官网首页 /ajax/top/{illust|manga} 的 body。[page] 各区块只给作品 id，
 * 作品本体统一放在 [thumbnails]，用 [artworks] 按区块顺序取回。
 */
data class WebTopBody(
    val page: Page? = null,
    val thumbnails: WebDiscoveryThumbnails? = null,
) : Serializable {

    /** 按 [ids] 的顺序取作品；thumbnails 里缺失、被遮罩或没有缩略图的条目直接跳过。 */
    fun artworks(ids: List<Long>?): List<WebDiscoveryArtwork> {
        if (ids.isNullOrEmpty()) return emptyList()
        val byId = thumbnails?.illust.orEmpty().associateBy { it.id }
        return ids.mapNotNull { id ->
            byId[id]?.takeIf { !it.isMasked && !it.url.isNullOrBlank() }
        }
    }
}
