package ceui.pixiv.paging

import ceui.pixiv.ui.common.ListItemHolder

interface ProtoToHolder<T> {
    fun mapper(entity: T): List<ListItemHolder>
}