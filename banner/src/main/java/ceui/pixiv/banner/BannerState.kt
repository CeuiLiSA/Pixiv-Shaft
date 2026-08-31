package ceui.pixiv.banner

sealed class BannerState {

    /** Nothing presenting; the queue may or may not be empty. */
    data object Idle : BannerState()

    /** A banner is currently visible to the user. */
    data class Presenting(val request: BannerRequest) : BannerState()

    /** Controller is shutting down — new requests will be dropped. */
    data object Shutdown : BannerState()
}
