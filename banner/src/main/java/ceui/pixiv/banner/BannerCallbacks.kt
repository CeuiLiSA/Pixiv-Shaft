package ceui.pixiv.banner

interface BannerCallbacks {

    fun dismiss(reason: BannerDismissReason = BannerDismissReason.Programmatic)
    fun triggerTap()
    fun triggerAction(actionKey: String?)
}
