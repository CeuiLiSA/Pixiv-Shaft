@file:JvmName("UserModelConverter")

package ceui.loxia

import ceui.lisa.models.ProfileImageUrlsBean
import ceui.lisa.models.TagsBean
import ceui.lisa.models.UserBean
import ceui.lisa.models.UserModel

/**
 * [UserModel] (legacy Java model) → [AccountResponse],逐字段显式映射,
 * 与 EmailBackupV3ViewModel 里 AccountResponse → UserModel 的 gson 桥互为反向。
 *
 * Java 调用:UserModelConverter.toAccountResponse(userModel)
 */
fun UserModel.toAccountResponse(): AccountResponse {
    return AccountResponse(
        // getAccess_token() 会拼上 "Bearer " 前缀,这里要的是原始 token
        access_token = rawAccessToken,
        expires_in = expires_in,
        refresh_token = refresh_token,
        scope = scope,
        token_type = token_type,
        user = user?.toUser(),
    )
}

fun UserBean.toUser(): User {
    return User(
        account = account,
        id = id.toLong(),
        name = name,
        comment = comment,
        mail_address = mail_address,
        is_premium = isIs_premium,
        is_followed = isIs_followed,
        is_mail_authorized = isIs_mail_authorized,
        require_policy_agreement = isRequire_policy_agreement,
        x_restrict = x_restrict,
        is_access_blocking_user = isIs_access_blocking_user,
        is_accept_request = isIs_accept_request,
        profile_image_urls = profile_image_urls?.let {
            ImageUrls(
                px_16x16 = it.px_16x16,
                px_50x50 = it.px_50x50,
                px_170x170 = it.px_170x170,
            )
        },
    )
}

/**
 * loxia [User] → legacy [UserBean]。IllustsBean 已并入 [Illust]，但 UserBean 仍是不少 legacy
 * 链路（关注 / 屏蔽画师 / 旧用户页）的入参类型，作品上的 user 要喂过去时走这一步。
 * 显式起个名再进 apply：两边都有 profile_image_urls / name / account 这些同名成员，
 * 在 apply 块里裸写会静默解析到 UserBean 自己那个还是空的字段。
 */
fun User.toUserBean(): UserBean {
    val source = this
    return UserBean().apply {
        id = source.id.toInt()
        name = source.name
        account = source.account
        comment = source.comment
        mail_address = source.mail_address
        isIs_premium = source.is_premium == true
        isIs_followed = source.is_followed == true
        isIs_mail_authorized = source.is_mail_authorized == true
        isRequire_policy_agreement = source.require_policy_agreement == true
        x_restrict = source.x_restrict ?: 0
        isIs_access_blocking_user = source.is_access_blocking_user == true
        isIs_accept_request = source.is_accept_request == true
        source.profile_image_urls?.let { urls ->
            profile_image_urls = ProfileImageUrlsBean().apply {
                medium = urls.medium ?: urls.px_170x170
                px_16x16 = urls.px_16x16
                px_50x50 = urls.px_50x50
                px_170x170 = urls.px_170x170 ?: urls.medium
            }
        }
    }
}

/** loxia [Tag] → legacy [TagsBean]（TagAdapter / 屏蔽标签等 legacy 入参仍是 TagsBean）。 */
fun Tag.toTagsBean(): TagsBean {
    val source = this
    return TagsBean().apply {
        name = source.name
        translated_name = source.translated_name
    }
}

fun List<Tag>.toTagsBeans(): List<TagsBean> = map { it.toTagsBean() }
