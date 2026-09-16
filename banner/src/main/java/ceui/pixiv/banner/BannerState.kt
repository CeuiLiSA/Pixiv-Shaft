package ceui.pixiv.banner

sealed class BannerState {

    /** Nothing presenting; the queue may or may not be empty. */
    data object Idle : BannerState()

    /** A banner handed to a started host, so a collector exists to render it. */
    data class Presenting(val request: BannerRequest) : BannerState()

    /** Controller is shutting down — new requests will be dropped. */
    data object Shutdown : BannerState()
}
