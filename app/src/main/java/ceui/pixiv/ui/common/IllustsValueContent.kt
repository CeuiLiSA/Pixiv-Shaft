package ceui.pixiv.ui.common

import ceui.loxia.IllustResponse
import kotlinx.coroutines.CoroutineScope

class IllustsValueContent(
    coroutineScope: CoroutineScope,
    dataFetcher: suspend () -> IllustResponse,
) : ValueContent<IllustResponse>(coroutineScope, dataFetcher, null) {

    override fun hasContent(valueT: IllustResponse): Boolean {
        return valueT.displayList.isNotEmpty()
    }
}