package ceui.pixiv.plaza

import ceui.pixiv.api.model.Illust

/** Mirrors the nine-upload cap: a linked work shows at most nine pages. */
const val PLAZA_LINKED_PAGE_LIMIT = 9

/** One page of a linked work, rendered in the post's media grid like an upload. */
data class PlazaLinkedPage(val index: Int, val medium: String, val large: String?)

/**
 * The work a post shows as its media: only when the author uploaded nothing, because uploads
 * are what they chose to show and the linked work then stays a capsule.
 */
fun PlazaPost.linkedWork(): Illust? =
    if (images.isEmpty()) objectExtensions?.illust?.takeIf { it.id > 0 } else null

/** Pages with a renderable `medium` URL; multi-page works carry page 0 inside meta_pages too. */
fun Illust.linkedPages(): List<PlazaLinkedPage> {
    val pages =
        meta_pages?.mapNotNull { it.image_urls }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(image_urls)
    return pages
        .take(PLAZA_LINKED_PAGE_LIMIT)
        .mapIndexedNotNull { index, urls ->
            urls.medium?.takeIf { it.isNotBlank() }?.let { PlazaLinkedPage(index, it, urls.large) }
        }
}

/**
 * What the composer attaches: the work exactly as the app-api returned it. The server owns the
 * whitelist (pximg-only URLs, no account-specific fields, at most nine pages) and rejects a work
 * without a `medium` page URL, so such a work attaches nothing instead of failing the post.
 */
fun Illust.plazaExtensions(): PlazaObjectExtensions? =
    if (image_urls?.medium.isNullOrBlank()) null else PlazaObjectExtensions(illust = this)
