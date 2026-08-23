package ceui.loxia

import ceui.lisa.models.ModelObject
import ceui.lisa.models.ObjectSpec
import ceui.lisa.models.Starable
import ceui.lisa.models.UserContainer
import java.io.Serializable

object ConstantUser {
    const val pixiv = 11L
    const val pxv_sensei = 17391869L
    const val mangapixiv = 14792128L
    const val pixivision = 12848282L
    const val pxv_sketch = 15241365L
    const val pixiv3 = 1085317L
    const val fanbox = 20390859L

    const val CeuiLiSA = 31660292L
    const val VOLUNTEER_USER_1 = 89989626L
    const val VOLUNTEER_USER_2 = 81263065L

    val officialUsers = listOf(pixiv, pxv_sensei, mangapixiv, pixivision, pxv_sketch, pixiv3, fanbox)
    val volunteerUsers = listOf(CeuiLiSA, VOLUNTEER_USER_1, VOLUNTEER_USER_2)
}

object UserGender {
    const val UNKNOWN = 0
    const val MALE = 1
    const val FEMALE = 2

    fun random(): Int = listOf(UNKNOWN, MALE, FEMALE).random()
}

/**
 * App 内唯一的用户模型。
 *
 * 字段同时覆盖 pixiv 的公开用户响应和旧 Java 用户模型在账号本地存储里追加的客户端字段，
 * 让既有 Gson JSON 可以直接反序列化，无需数据库或偏好迁移。属性保留可变性是为了兼容仍在
 * Java 层命令式更新账号/关注态的调用点；跨列表同步仍应优先通过 [copy] 换实例。
 */
data class User(
    var account: String? = null,
    var id: Long = 0L,
    var user_id: Long = 0L,
    var is_followed: Boolean? = null,
    var name: String? = null,
    var pixiv_id: String? = null,
    var profile_image_urls: ImageUrls? = null,
    var is_mail_authorized: Boolean? = null,
    var is_premium: Boolean? = null,
    var mail_address: String? = null,
    var gender: Int = UserGender.MALE,
    var require_policy_agreement: Boolean? = null,
    var x_restrict: Int? = null,
    var comment: String? = null,
    var is_access_blocking_user: Boolean? = null,
    var is_accept_request: Boolean? = null,
    // 旧账号 JSON 中的客户端字段，保留同名 key 以保证覆盖安装后可无损读取。
    var password: String? = null,
    var is_login: Boolean = false,
    var lastTokenTime: Long = -1L,
) : Serializable, ModelObject, UserContainer, Starable {

    override val objectUniqueId: Long get() = id
    override val objectType: Int get() = ObjectSpec.KUser

    override fun getUserId(): Int = id.toInt()
    override fun getItemID(): Int = id.toInt()
    override fun setItemID(id: Int) {
        this.id = id.toLong()
    }

    override fun isItemStared(): Boolean = is_followed == true
    override fun setItemStared(isLiked: Boolean) {
        is_followed = isLiked
    }

    fun isOfficial(): Boolean = id in ConstantUser.officialUsers
    fun isVolunteer(): Boolean = id in ConstantUser.volunteerUsers
    fun hasGender(): Boolean = gender != UserGender.UNKNOWN
    fun exist(): Boolean = !name.isNullOrEmpty() || !account.isNullOrEmpty()
    fun isR18Enabled(): Boolean = (x_restrict ?: 0) != 0
    fun isR18GEnabled(): Boolean = x_restrict == 2
    fun withFollowed(followed: Boolean): User =
        if (is_followed == followed) this else copy(is_followed = followed)
}

data class ImageUrls(
    val url: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val original: String? = null,
    val small: String? = null,
    val square_medium: String? = null,
    val px_16x16: String? = null,
    val px_170x170: String? = null,
    val px_50x50: String? = null,
) : Serializable {
    fun findMaxSizeUrl(): String? =
        url ?: original ?: large ?: medium ?: square_medium ?: small ?: px_170x170 ?: px_50x50 ?: px_16x16
}
