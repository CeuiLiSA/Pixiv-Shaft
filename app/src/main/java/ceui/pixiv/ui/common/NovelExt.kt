package ceui.pixiv.ui.common

import ceui.loxia.Novel

/**
 * 小说封面 URL：app-api 小说列表只给缩略图，按清晰度取 large → medium → square_medium。
 * 列表卡片与作者页 banner 共用同一份取图规则，避免各处内联顺序不一致。
 */
val Novel.coverUrl: String?
    get() = image_urls?.let { it.large ?: it.medium ?: it.square_medium }
