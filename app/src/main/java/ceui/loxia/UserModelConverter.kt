@file:JvmName("UserModelConverter")

package ceui.loxia

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
