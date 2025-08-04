package ceui.pixiv.paging

import ceui.loxia.Illust
import ceui.loxia.KListShow
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListItemHolder

class PagingIllustAPIRepository(
    private val loader: suspend () -> KListShow<Illust>,
) : PagingAPIRepository<Illust>() {

    override suspend fun loadFirst(): KListShow<Illust> {
        return loader()
    }

    override fun mapper(entity: Illust): List<ListItemHolder> {
        return if (entity.isAuthurExist()) {
            return listOf(IllustCardHolder(entity))
        } else {
            emptyList()
        }
    }
}