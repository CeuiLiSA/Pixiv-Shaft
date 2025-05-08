package ceui.pixiv.ui.chats

import ceui.loxia.Client
import ceui.loxia.SquareResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.repo.ResponseStoreRepository
import ceui.pixiv.ui.settings.CookieNotSyncException
import com.tencent.mmkv.MMKV

class SquareRepository(
    private val objectType: String,
    private val prefStore: MMKV,
    responseStore: ResponseStore<SquareResponse>,
) : ResponseStoreRepository<SquareResponse>(responseStore) {

    override suspend fun fetchRemoteDataImpl(): SquareResponse {
        if (prefStore.getString(SessionManager.COOKIE_KEY, "").isNullOrEmpty()) {
            throw CookieNotSyncException("Pixiv cookie not synced")
        }

        return Client.webApi.getSquareContents(objectType)
    }
}