package ceui.pixiv.plaza

import ceui.lisa.models.ModelObject
import ceui.lisa.models.ObjectSpec

/** Account-specific reaction state and signed media URLs travel with the shared post.
 * A null post is a deletion notification for existing observers.
 * The API's objectType describes a linked Pixiv object, so it cannot implement ModelObject.
 */
data class PlazaPostCacheEntry(
    val id: Long,
    val viewerUid: Long,
    val post: PlazaPost?,
    val cachedAt: Long,
    val revision: Long,
) : ModelObject {
    override val objectUniqueId: Long get() = id
    override val objectType: Int get() = ObjectSpec.PLAZA_POST
}
