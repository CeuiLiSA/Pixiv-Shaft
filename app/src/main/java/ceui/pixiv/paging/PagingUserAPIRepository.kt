package ceui.pixiv.paging

import ceui.loxia.KListShow
import ceui.loxia.UserPreview
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.user.UserPreviewHolder

class PagingUserAPIRepository(
    private val loader: suspend () -> KListShow<UserPreview>,
) : PagingAPIRepository<UserPreview>() {

    override suspend fun loadFirst(): KListShow<UserPreview> {
        return loader()
    }

    override fun mapper(entity: UserPreview): List<ListItemHolder> {
        return listOf(UserPreviewHolder(entity))
    }
}