package ceui.pixiv.utils

import ceui.pixiv.api.model.Illust
import ceui.lisa.models.TagsBean
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

// 固定 tag 时把 {tag, resp:{illusts:[main]}} 序列化进 search_table.previewIllustsJson。
// shape 沿用旧 Prime 内置榜 assets 的 {tag, resp:{illusts}},illusts 当前只塞 1 张主图
// （用户长按所在的详情页 illust）。
fun buildPinnedTagPreviewJson(tag: TagsBean, illust: Illust? = null): String =
    buildPinnedTagPreviewJson(tag, listOfNotNull(illust))

// 搜索结果页置顶当前搜索（含标签组合）时用：塞结果页头几张作品，置顶卡片最多展示 3 张。
fun buildPinnedTagPreviewJson(tag: TagsBean, illusts: List<Illust>): String {
    val gson = Gson()
    return JsonObject().apply {
        add("tag", gson.toJsonTree(tag))
        add("resp", JsonObject().apply {
            add("illusts", JsonArray().apply { illusts.forEach { add(gson.toJsonTree(it)) } })
        })
    }.toString()
}

/** 历史/置顶行不展示译文，但长按菜单仍可使用保存的官方译名。旧的空预览返回 null。 */
fun pinnedTagTranslation(json: String?): String? {
    if (json.isNullOrBlank()) return null
    return try {
        Gson().fromJson(json, TagPreviewMetadata::class.java)?.tag?.translated_name
    } catch (_: com.google.gson.JsonParseException) {
        null // 兼容旧版或导入的损坏预览；不影响按原文搜索。
    }
}

private data class TagPreviewMetadata(@SerializedName("tag") val tag: TagsBean?)
