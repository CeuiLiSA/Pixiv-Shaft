package ceui.pixiv.ui.prime

import ceui.loxia.Tag
import com.google.gson.annotations.SerializedName

/**
 * `assets/pixiv_prime/prime_index.json` 里的一条：标签名 + 三张预览方图。
 *
 * 目录本身仍留在 APK 里（只有几十 KB，货架必须离线秒开），但每个标签背后的 300 条插画
 * 已经搬到 pixshaft-api 按页取——那份数据原本是 183MB 的 assets，占安装包约 19MB。
 */
data class PrimeTagIndexItem(
    val tag: Tag,
    @SerializedName("file_path")
    val filePath: String,
    @SerializedName("preview_square_urls")
    val previewSquareUrls: List<String> = emptyList()
) {

    /**
     * 服务端 `/v1/prime/tags/{key}/illusts` 的 key —— 就是老 assets 文件名里那段 sha256。
     *
     * 沿用 `file_path` 而不是给 index 加新字段：这份 json 由外部流程生成，少改一处口径少一处
     * 失配。取不到（文件名不合规）返回 null，调用方当作这条目录项不可点。
     */
    val tagKey: String?
        get() = TAG_KEY_RE.find(filePath)?.value

    private companion object {
        val TAG_KEY_RE = Regex("[0-9a-f]{64}")
    }
}
