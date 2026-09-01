package ceui.pixiv.api.model

import ceui.lisa.models.ModelObject
import ceui.lisa.models.ObjectSpec
import ceui.loxia.User

data class UserResponse(
    val profile: Profile? = null,
    val profile_publicity: ProfilePublicity? = null,
    val user: User? = null,
    val workspace: Workspace? = null,
    // user/detail v2 新增:被服务端禁用的外链标识列表(空数组表示无)
    val disabled_links: List<String>? = null
) : ModelObject {

    fun isPremium(): Boolean {
        return profile?.is_premium == true
    }

    override val objectUniqueId: Long
        get() = user?.id ?: 0L
    override val objectType: Int
        get() = ObjectSpec.UserProfile
}