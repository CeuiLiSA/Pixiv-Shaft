package ceui.lisa.models

import java.io.Serializable

data class WebUser(
    val userId: Long? = null,
    val partial: Long? = null,
    val comment: String? = null,
    val name: String? = null,
    val image: String? = null,
    val imageBig: String? = null,
    val followedBack: Boolean? = null,
    val premium: Boolean? = null,
    val isFollowed: Boolean? = null,
    val isMypixiv: Boolean? = null,
    val isBlocking: Boolean? = null,
    val acceptRequest: Boolean? = null
) : Serializable
