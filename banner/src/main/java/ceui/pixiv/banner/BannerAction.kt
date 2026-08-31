package ceui.pixiv.banner

data class BannerAction(
    val label: String,
    val deepLink: String? = null,
    val actionKey: String? = null,
)
