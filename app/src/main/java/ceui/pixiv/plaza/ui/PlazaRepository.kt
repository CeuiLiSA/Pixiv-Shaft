package ceui.pixiv.plaza.ui

import ceui.lisa.network.*
import kotlinx.coroutines.flow.Flow

/** Plaza transport and cross-screen updates, replaceable in state regression tests. */
open class PlazaRepository {
    open val plazaPostsCreated: Flow<PlazaPost> get() = ShaftApiV2Client.plazaPostsCreated
    open val plazaPostsDeleted: Flow<Long> get() = ShaftApiV2Client.plazaPostsDeleted
    open val plazaPostsUpdated: Flow<PlazaPost> get() = ShaftApiV2Client.plazaPostsUpdated
    open fun cachedPlazaPost(id: Long) = ShaftApiV2Client.cachedPlazaPost(id)
    open fun broadcastPostUpdated(post: PlazaPost) = ShaftApiV2Client.broadcastPostUpdated(post)
    open suspend fun listPlazaFeed(limit: Int, before: Long?, viewerUid: Long) =
        ShaftApiV2Client.listPlazaFeed(limit, before, viewerUid)
    open suspend fun getPlazaPost(id: Long, viewerUid: Long) =
        ShaftApiV2Client.getPlazaPost(id, viewerUid)
    open suspend fun listPlazaComments(postId: Long, limit: Int, before: Long?) =
        ShaftApiV2Client.listPlazaComments(postId, limit, before)
    open suspend fun createPlazaComment(uid: Long, postId: Long, text: String) =
        ShaftApiV2Client.createPlazaComment(uid, postId, text)
    open suspend fun likePlazaPost(uid: Long, postId: Long) = ShaftApiV2Client.likePlazaPost(uid, postId)
    open suspend fun unlikePlazaPost(uid: Long, postId: Long) = ShaftApiV2Client.unlikePlazaPost(uid, postId)
    open suspend fun deletePlazaPost(uid: Long, postId: Long) = ShaftApiV2Client.deletePlazaPost(uid, postId)
}
