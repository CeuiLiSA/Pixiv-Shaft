package ceui.pixiv.ui.user

import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.UserResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.repo.ResponseStoreRepository

class MineProfileRepository(
    responseStore: ResponseStore<UserResponse>,
) : ResponseStoreRepository<UserResponse>(responseStore) {
    override suspend fun fetchRemoteDataImpl(): UserResponse {
        val resp = Client.appApi.getUserProfile(SessionManager.loggedInUid)
        resp.user?.let {
            ObjectPool.update(it)
        }
        ObjectPool.update(resp)
        return resp
    }
}