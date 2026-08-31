package ceui.pixiv.banner

sealed class BannerDismissReason {

    data object AutoTimeout : BannerDismissReason()
    data object UserSwipe : BannerDismissReason()
    data object UserTap : BannerDismissReason()
    data object Programmatic : BannerDismissReason()
    data object Preempted : BannerDismissReason()
    data class Replaced(val byId: String) : BannerDismissReason()
}
