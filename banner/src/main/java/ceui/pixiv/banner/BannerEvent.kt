package ceui.pixiv.banner

sealed class BannerEvent {

    data class Shown(val id: String) : BannerEvent()

    data class Dismissed(val id: String, val reason: BannerDismissReason) : BannerEvent()

    data class Tapped(val id: String, val deepLink: String?) : BannerEvent()

    data class Action(
        val id: String,
        val actionKey: String?,
        val deepLink: String?,
    ) : BannerEvent()

    data class Dropped(val id: String, val cause: DropCause) : BannerEvent()

    data class QueueOverflow(val droppedId: String) : BannerEvent()
}

enum class DropCause { DUPLICATE_DROPPED, POLICY_REJECTED, QUEUE_FULL, SHUTDOWN }
