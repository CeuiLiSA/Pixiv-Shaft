package ceui.pixiv.download.model

import java.time.Instant

data class ItemMeta(
    val id: Long,
    val title: String,
    val author: Author,
    val createdAt: Instant,
    val page: Int? = null,
    val totalPages: Int = 1,
    val width: Int? = null,
    val height: Int? = null,
    val flags: Set<Flag> = emptySet(),
) {
    fun has(flag: Flag): Boolean = flag in flags
    val isMultiPage: Boolean get() = totalPages > 1
}
