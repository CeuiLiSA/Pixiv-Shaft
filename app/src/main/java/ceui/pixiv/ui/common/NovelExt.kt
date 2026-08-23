package ceui.pixiv.ui.common

import ceui.loxia.Novel

/** 作者没设封面时 app-api 回的占位图路径 —— 拿它当背景/补位图等于铺一张灰底。 */
private const val NOVEL_THUMB_PLACEHOLDER = "/common/images/novel_thumb/"

/**
 * 「能看的」封面 URL：[Novel.resolvedCoverUrl]（large → medium → square_medium）去掉
 * app-api 的占位图。
 * 用于拿小说封面当图使的场景（作者页 banner、关注列表预览格补位）——那里宁可留空也不该铺灰底。
 * 小说列表卡片不用它：卡片的封面位固定要占着，占位图也得画。
 */
val Novel.realCoverUrl: String?
    get() = resolvedCoverUrl()?.takeUnless { it.contains(NOVEL_THUMB_PLACEHOLDER) }
