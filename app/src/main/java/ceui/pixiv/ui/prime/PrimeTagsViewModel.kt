package ceui.pixiv.ui.prime

import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.HoldersViewModel
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrimeTagsViewModel : HoldersViewModel() {

    private val gson = Gson()

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        withContext(Dispatchers.IO) {
            val assetManager = Utils.getApp().assets
            val paths = loadPrimeTxtFullPaths()

            val items = paths.map { path ->
                val json = assetManager.open(path).bufferedReader().use { it.readText() }
                val result = gson.fromJson(json, PrimeTagResult::class.java)
                PrimeTagItemHolder(result, path)
            }

            _itemHolders.postValue(items)
        }
        _refreshState.value = RefreshState.LOADED(hasContent = true, hasNext = false)
    }


    private fun loadPrimeTxtFullPaths(): List<String> {
        val files =
            Utils.getApp().applicationContext.assets.list(DIR) ?: return emptyList()
        return files
            .filter { it.endsWith(".txt", ignoreCase = true) }
            .map { "${DIR}/$it" }
    }

    companion object {
        private const val DIR = "pixiv_prime"
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }
}

