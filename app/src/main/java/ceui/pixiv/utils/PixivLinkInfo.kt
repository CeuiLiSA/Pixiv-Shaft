package ceui.pixiv.utils


data class PixivLinkInfo(val type: String, val value: String)

fun extractPixivId(url: String): PixivLinkInfo {
    // 更新正则表达式，支持 pixiv:// 类型（novels, illusts, users）以及普通 https 链接
    val pixivRegex = """pixiv://(novels|illusts|users)/(\d+)""".toRegex()

    // 先尝试匹配 pixiv:// 类型
    val pixivMatchResult = pixivRegex.find(url)
    if (pixivMatchResult != null) {
        val type = pixivMatchResult.groupValues[1]
        val id = pixivMatchResult.groupValues[2]
        return PixivLinkInfo(type, id)
    }

    // 如果都没匹配到，返回默认值
    return PixivLinkInfo("others", url)
}