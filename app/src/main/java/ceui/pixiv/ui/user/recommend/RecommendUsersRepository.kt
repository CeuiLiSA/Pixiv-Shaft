package ceui.pixiv.ui.user.recommend

import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.UserPreview
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.paging.PagingAPIRepository
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.user.UserPreviewHolder

class RecommendUsersRepository : PagingAPIRepository<UserPreview>() {
    override val recordType: Int
        get() = RecordType.PAGING_DATA_HOME_RECMMEND_USER

    override suspend fun loadFirst(): KListShow<UserPreview> {
        return Client.appApi.recommendedUsers()
    }

    override fun mapper(entity: GeneralEntity): List<ListItemHolder> {
        return listOf(UserPreviewHolder(entity.typedObject()))
    }

}