package ceui.lisa.repo

/** 「某人创作的插画」按 offset 定位的翻页 URL（UserIllustJumpHelper 跳页用）。 */
internal fun buildOffsetUrl(userID: Long, type: String, offset: Int): String =
    "https://app-api.pixiv.net/v1/user/illusts" +
        "?filter=for_android&user_id=$userID&type=$type&offset=$offset"
