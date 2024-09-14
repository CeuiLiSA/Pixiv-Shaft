package ceui.pixiv.ui.common

import ceui.loxia.KListShow


interface DataSourceContainer<Item, T: KListShow<Item>> {

    fun dataSource(): DataSource<*, *>

    fun <DataSourceT: DataSource<Item, T>> typedDataSource(): DataSourceT
}