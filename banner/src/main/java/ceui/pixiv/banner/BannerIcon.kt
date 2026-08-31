package ceui.pixiv.banner

sealed interface BannerIcon {

    /** Resource id from the host module's `R.drawable`. */
    data class Resource(val resId: Int) : BannerIcon

    /** Remote image to be fetched and decoded by the image pipeline. */
    data class Url(val url: String) : BannerIcon
}
