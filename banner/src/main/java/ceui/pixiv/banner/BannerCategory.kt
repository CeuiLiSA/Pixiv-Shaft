package ceui.pixiv.banner

sealed class BannerCategory {

    abstract val key: String

    data object Chat : BannerCategory() {
        override val key: String = "chat"
    }

    data object System : BannerCategory() {
        override val key: String = "system"
    }
}
