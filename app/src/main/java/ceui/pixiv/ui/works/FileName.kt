package ceui.pixiv.ui.works

import ceui.loxia.Novel


fun buildPixivWorksFileName(illustId: Long, index: Int = 0): String {
    return "pixiv_works_${illustId}_p${index}.png"
}

fun buildUgoraWorksFileName(illustId: Long): String {
    return "pixiv_works_${illustId}_ugora.webp"
}

fun buildPixivNovelFileName(novel: Novel): String {
    val title = novel.title
    if (title?.isNotEmpty() == true) {
        // 移除文件名中不允许的特殊字符，仅保留合法字符
        val sanitizedTitle = novel.title.replace(Regex("[\\\\/:*?\"<>|]"), "")
        // 截取前12个字符（确保中文和其他多字节字符不会被破坏）
        val truncatedTitle = sanitizedTitle.trim().take(24)
        // 拼接文件名，带上 novel.id 和后缀
        return "${truncatedTitle}_ID${novel.id}.txt"
    } else {
        return "novel_ID${novel.id}.txt"
    }
}