package ceui.pixiv.banner

sealed interface BannerRequest {

    val id: String
    val dedupKey: String?
    val priority: BannerPriority
    val category: BannerCategory
    val policy: BannerDisplayPolicy

    /** `null` means sticky — the banner stays until dismissed explicitly. */
    val autoDismissMillis: Long?

    val deepLink: String?
    val metadata: Map<String, String>

    /** A simple title/message banner with optional icon and action button. */
    data class Text(
        override val id: String,
        val title: String,
        val message: String? = null,
        val icon: BannerIcon? = null,
        val action: BannerAction? = null,
        override val dedupKey: String? = null,
        override val priority: BannerPriority = BannerPriority.NORMAL,
        override val category: BannerCategory = BannerCategory.System,
        override val policy: BannerDisplayPolicy = BannerDisplayPolicy.Enqueue,
        override val autoDismissMillis: Long? = 4000L,
        override val deepLink: String? = null,
        override val metadata: Map<String, String> = emptyMap(),
    ) : BannerRequest

    /**
     * A banner whose visual layout is supplied by a feature-specific binder
     * registered under [binderKey]. [payload] is forwarded to the binder as-is.
     */
    data class Custom(
        override val id: String,
        val binderKey: String,
        val payload: Any,
        override val dedupKey: String? = null,
        override val priority: BannerPriority = BannerPriority.NORMAL,
        override val category: BannerCategory = BannerCategory.System,
        override val policy: BannerDisplayPolicy = BannerDisplayPolicy.Enqueue,
        override val autoDismissMillis: Long? = 4000L,
        override val deepLink: String? = null,
        override val metadata: Map<String, String> = emptyMap(),
    ) : BannerRequest
}
