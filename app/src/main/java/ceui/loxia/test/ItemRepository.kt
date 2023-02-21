package ceui.loxia.test

import ceui.loxia.RefreshState
import ceui.loxia.Repository
import ceui.refactor.AAAAHolder
import ceui.refactor.BBBBHolder
import ceui.refactor.ListItemHolder
import kotlinx.coroutines.delay

class ItemRepository : Repository<ItemFragment>() {

    override suspend fun refresh(fragment: ItemFragment) {
        delay(1500L)
        val list = mutableListOf<ListItemHolder>()
        for (index in 0..100) {
            if (index % 2 == 0) {
                list.add(AAAAHolder(index.toString(), "我是AA第${index}个数据").onItemClick { sender ->
                })
            } else {
                list.add(BBBBHolder(index.toString(), "我是BB第${index}个数据").onItemClick { sender ->
                })
            }
        }
        holderList.value = list
        refreshState.value = RefreshState.LOADED(hasContent = true, hasNext = false)
    }

    override suspend fun loadMore(fragment: ItemFragment) {
    }
}