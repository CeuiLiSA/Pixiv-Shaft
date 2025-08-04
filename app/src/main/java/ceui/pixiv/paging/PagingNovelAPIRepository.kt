package ceui.pixiv.paging

import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.NovelCardHolder

class PagingNovelAPIRepository(
    private val loader: suspend () -> KListShow<Novel>,
) : PagingAPIRepository<Novel>() {

    override suspend fun loadFirst(): KListShow<Novel> {
        return loader()
    }

    override fun mapper(entity: Novel): List<ListItemHolder> {
        return if (entity.visible == true) {
            return listOf(NovelCardHolder(entity))
        } else {
            emptyList()
        }
    }
}