package ceui.pixiv.ui.novel

import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 小说详情页头的几条纯计算（无 Android 依赖，JVM 单测见 NovelDetailMathTest）。
 */

/**
 * pixiv 封面缩略图 → 母图：`https://i.pximg.net/c/240x480_80/novel-cover-master/img/…`
 * 去掉 `/c/<尺寸>/` 这一段就是作者上传的原尺寸封面（实测 240×338 → 640×900）。
 * 只认 `novel-cover-master` 路径；默认封面（`s.pximg.net/common/images/novel_thumb/…`）
 * 和其它形状一律返回 null —— 调用方据此判断「有没有真封面」（没有就不做模糊背景）。
 */
fun novelCoverMasterUrl(thumbnailUrl: String?): String? {
    if (thumbnailUrl.isNullOrEmpty()) return null
    val m = COVER_THUMBNAIL.matchEntire(thumbnailUrl) ?: return null
    return m.groupValues[1] + "/" + m.groupValues[2]
}

private val COVER_THUMBNAIL = Regex("^(https?://[^/]+)/c/[^/]+/(novel-cover-master/.+)$")

/** pixiv 网页端「约 N 分钟」的口径：每分钟 500 字。 */
const val NOVEL_READING_CHARS_PER_MINUTE = 500

/** 预计阅读分钟数，至少 1 分钟；字数未知或为 0 时返回 null（不显示这一项）。 */
fun novelReadingMinutes(textLength: Int?): Int? {
    if (textLength == null || textLength <= 0) return null
    return max(1, (textLength.toDouble() / NOVEL_READING_CHARS_PER_MINUTE).roundToInt())
}

/**
 * 阅读进度（1–100），没读过返回 null（显示「阅读正文」）。
 *
 * 阅读器两种模式存的东西不同（见 NovelReaderV3ViewModel 的保存点）：分页模式存
 * (首字偏移, 页号, 总页数)，按页算；滚动模式只存字符偏移、页号和总页数为 0，按字数算。
 * 一打开就退出（偏移和页号都是 0）算没读过。
 */
fun novelReadingPercent(charIndex: Int, pageIndex: Int, totalPages: Int, textLength: Int?): Int? {
    if (charIndex <= 0 && pageIndex <= 0) return null
    val raw = when {
        totalPages > 0 -> ((pageIndex + 1).toLong() * 100 / totalPages).toInt()
        textLength != null && textLength > 0 -> (charIndex.toLong() * 100 / textLength).toInt()
        else -> return null
    }
    return raw.coerceIn(1, 100)
}
