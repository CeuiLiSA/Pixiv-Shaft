package ceui.pixiv.ui.search.v3

import com.google.gson.annotations.SerializedName

/**
 * Maps `/v1/search/options` — pixiv 官方在新版 iOS app 用来动态拉「当前账号可选的筛选选项」
 * 的接口。illust / novel 两个 scope 各自带一份，结构基本对称（illust 多 tool，novel 多 genre
 * 与字数适用语言说明）。
 */
data class SearchOptionsResponse(
    @SerializedName("illust") val illust: SearchOptionsScope? = null,
    @SerializedName("novel")  val novel:  SearchOptionsScope? = null,
)

data class SearchOptionsScope(
    @SerializedName("bookmark_ranges")        val bookmarkRanges: List<BookmarkRange>? = null,
    @SerializedName("show_ai_condition")      val showAiCondition: Boolean = false,
    @SerializedName("tool")                   val tool: ToolOptions? = null,
    @SerializedName("genre")                  val genre: GenreOptions? = null,
    @SerializedName("lang")                   val lang: LangOptions? = null,
    @SerializedName("word_count_supported_languages") val wordCountSupportedLanguages: String? = null,
)

/**
 * `"*"` 在 pixiv 协议里是「不限」哨兵。客户端遇到要降级成 null。
 */
data class BookmarkRange(
    @SerializedName("bookmark_num_min") val min: String? = null,
    @SerializedName("bookmark_num_max") val max: String? = null,
) {
    fun minInt(): Int? = min?.takeIf { it != "*" }?.toIntOrNull()
    fun maxInt(): Int? = max?.takeIf { it != "*" }?.toIntOrNull()

    /** 转成 filter 层的区间；两端都不限（`"*"`/`"*"`，即官方列表里的「所有点赞数」项）返回 null。 */
    fun toSpec(): BookmarkRangeSpec? {
        val minValue = minInt()
        val maxValue = maxInt()
        return if (minValue == null && maxValue == null) null else BookmarkRangeSpec(minValue, maxValue)
    }
}

data class ToolOptions(
    @SerializedName("options") val options: List<String> = emptyList(),
)

data class GenreOptions(
    @SerializedName("options") val options: List<GenreOption> = emptyList(),
)

data class GenreOption(
    @SerializedName("id")    val id: Int,
    @SerializedName("label") val label: String,
)

data class LangOptions(
    @SerializedName("options") val options: List<LangOption> = emptyList(),
)

data class LangOption(
    @SerializedName("code") val code: String,
    @SerializedName("name") val name: String,
)
