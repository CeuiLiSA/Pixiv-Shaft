package ceui.pixiv.ui.prime

import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.HoldersViewModel
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrimeTagsViewModel : HoldersViewModel() {

    private val gson = Gson()

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        withContext(Dispatchers.IO) {
            val assetManager = Utils.getApp().assets
            val json = assetManager.open(INDEX_FILE).bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<PrimeTagIndexItem>>() {}.type
            val indexItems: List<PrimeTagIndexItem> = gson.fromJson(json, type)

            val items = indexItems.map { entry ->
                PrimeTagItemHolder(entry)
            }

            _itemHolders.postValue(items)
        }
        _refreshState.value = RefreshState.LOADED(hasContent = true, hasNext = false)
    }

    companion object {
        private const val INDEX_FILE = "pixiv_prime/prime_index.json"
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }
}
