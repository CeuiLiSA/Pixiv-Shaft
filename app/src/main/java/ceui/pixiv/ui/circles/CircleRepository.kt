package ceui.pixiv.ui.circles

import ceui.loxia.CircleResponse
import ceui.loxia.Client
import ceui.pixiv.ui.common.ResponseStore
import ceui.pixiv.ui.common.repo.ResponseStoreRepository

class CircleRepository(
    private val keyword: String,
    responseStore: ResponseStore<CircleResponse>,
) : ResponseStoreRepository<CircleResponse>(responseStore) {
    override suspend fun fetchRemoteDataImpl(): CircleResponse {
        return Client.webApi.getCircleDetail(keyword)
    }
}