package ceui.pixiv.utils

import ceui.lisa.models.IllustsBean
import ceui.lisa.models.TagsBean
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

// 固定 tag 时把 {tag, resp:{illusts:[main]}} 序列化进 search_table.previewIllustsJson。
// shape 沿用旧 Prime 内置榜 assets 的 {tag, resp:{illusts}},illusts 当前只塞 1 张主图
// （用户长按所在的详情页 illust）。
fun buildPinnedTagPreviewJson(tag: TagsBean, illust: IllustsBean): String {
    val gson = Gson()
    return JsonObject().apply {
        add("tag", gson.toJsonTree(tag))
        add("resp", JsonObject().apply {
            add("illusts", JsonArray().apply { add(gson.toJsonTree(illust)) })
        })
    }.toString()
}
