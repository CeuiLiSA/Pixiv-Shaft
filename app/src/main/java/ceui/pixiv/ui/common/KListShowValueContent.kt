package ceui.pixiv.ui.common

import ceui.loxia.KListShow
import kotlinx.coroutines.CoroutineScope

class KListShowValueContent<ValueT: KListShow<*>>(
    coroutineScope: CoroutineScope,
    dataFetcher: suspend () -> ValueT,
) : ValueContent<ValueT>(coroutineScope, dataFetcher, null) {

    override fun hasContent(valueT: ValueT): Boolean {
        return valueT.displayList.isNotEmpty()
    }
}