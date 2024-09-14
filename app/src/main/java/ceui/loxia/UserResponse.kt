package ceui.loxia

import ceui.lisa.models.ModelObject
import ceui.lisa.models.ObjectSpec

data class UserResponse(
    val profile: Profile? = null,
    val profile_publicity: ProfilePublicity? = null,
    val user: User? = null,
    val workspace: Workspace? = null
) : ModelObject {

    fun isPremium(): Boolean {
        return profile?.is_premium == true
    }

    override val objectUniqueId: Long
        get() = user?.id ?: 0L
    override val objectType: Int
        get() = ObjectSpec.UserProfile
}