package ceui.pixiv.ui.prime

import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object PrimeIllustLoader {

    private val gson = Gson()

    private val indexItems: List<PrimeTagIndexItem> by lazy {
        try {
            val json = Utils.getApp().assets.open(INDEX_FILE).bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<PrimeTagIndexItem>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun matchesKeyword(keyword: String?): Boolean {
        if (keyword.isNullOrBlank()) return false
        val lower = keyword.lowercase()
        return indexItems.any { tagMatchesKeyword(it, lower) }
    }

    fun loadForKeyword(keyword: String?): ListIllust? {
        if (keyword.isNullOrBlank()) return null
        val lower = keyword.lowercase()
        val matched = indexItems.filter { tagMatchesKeyword(it, lower) }
        if (matched.isEmpty()) return null

        val allIllusts = mutableListOf<IllustsBean>()
        val assetManager = Utils.getApp().assets
        for (entry in matched) {
            try {
                val json = assetManager.open(entry.filePath).bufferedReader().use { it.readText() }
                val result = gson.fromJson(json, PrimeTagResultLegacy::class.java)
                result?.resp?.illusts?.let { allIllusts.addAll(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (allIllusts.isEmpty()) return null

        val result = ListIllust()
        result.setIllusts(ArrayList(allIllusts))
        return result
    }

    private fun tagMatchesKeyword(item: PrimeTagIndexItem, lowerKeyword: String): Boolean {
        val tagName = item.tag.name?.lowercase() ?: ""
        val translatedName = item.tag.translated_name?.lowercase() ?: ""
        return (tagName.isNotEmpty() && (tagName.contains(lowerKeyword) || lowerKeyword.contains(tagName))) ||
                (translatedName.isNotEmpty() && (translatedName.contains(lowerKeyword) || lowerKeyword.contains(translatedName)))
    }

    private const val INDEX_FILE = "pixiv_prime/prime_index.json"
}

private data class PrimeTagResultLegacy(
    val tag: Any? = null,
    val resp: PrimeTagResultResp? = null
)

private data class PrimeTagResultResp(
    val illusts: List<IllustsBean>? = null,
    val next_url: String? = null
)
