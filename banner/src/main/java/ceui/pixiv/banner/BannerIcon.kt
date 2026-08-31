package ceui.pixiv.banner

sealed interface BannerIcon {

    /** Resource id from the host module's `R.drawable`. */
    data class Resource(val resId: Int) : BannerIcon

    /**
     * Remote image. The banner module has no image pipeline of its own — the
     * host supplies a [BannerIconLoader] to [DefaultBannerViewBinder]; without
     * one, URL icons are simply not shown.
     */
    data class Url(val url: String) : BannerIcon
}
