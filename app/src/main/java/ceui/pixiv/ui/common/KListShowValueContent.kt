package ceui.pixiv.ui.common

import ceui.loxia.KListShow
import ceui.pixiv.ui.common.repo.RemoteRepository
import kotlinx.coroutines.CoroutineScope

class KListShowValueContent<ValueT: KListShow<*>>(
    coroutineScope: CoroutineScope,
    dataFetcher: suspend () -> ValueT,
) : ValueContent<ValueT>(coroutineScope, RemoteRepository(dataFetcher)) {

    override fun hasContent(valueT: ValueT): Boolean {
        return valueT.displayList.isNotEmpty()
    }
}