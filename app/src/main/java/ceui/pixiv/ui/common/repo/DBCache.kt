package ceui.pixiv.ui.common.repo

data class DBCache<ValueT>(
    val obj: ValueT,
    val updatedTime: Long,
)
