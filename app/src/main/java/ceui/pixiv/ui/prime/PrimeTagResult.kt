package ceui.pixiv.ui.prime

import ceui.loxia.IllustResponse
import ceui.loxia.Tag
import com.google.gson.annotations.SerializedName

data class PrimeTagResult(
    val tag: Tag,
    val resp: IllustResponse
)

data class PrimeTagIndexItem(
    val tag: Tag,
    @SerializedName("file_path")
    val filePath: String,
    @SerializedName("preview_square_urls")
    val previewSquareUrls: List<String> = emptyList()
)