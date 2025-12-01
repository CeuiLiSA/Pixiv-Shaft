package ceui.pixiv.ui.prime

import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.IllustCardHolder
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrimeTagDetailViewModel(
    private val filePath: String,
) : HoldersViewModel() {

    private val gson = Gson()

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        withContext(Dispatchers.IO) {
            val assetManager = Utils.getApp().assets
            val json = assetManager.open(filePath).bufferedReader().use { it.readText() }
            val result = gson.fromJson(json, PrimeTagResult::class.java)

            _itemHolders.postValue(result.resp.displayList.map { illust ->
                IllustCardHolder(illust)
            })
        }
        _refreshState.value = RefreshState.LOADED(hasContent = true, hasNext = false)
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }
}