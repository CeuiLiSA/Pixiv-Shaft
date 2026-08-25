package ceui.lisa.http

import ceui.lisa.models.AccountEditResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * accounts.pixiv.net 的账号编辑接口（改密码 / 改邮箱 / 改 pixiv ID）。
 *
 * 旧版按「改哪几项」拆了 7 个重载，其实都是同一个端点；null 字段 Retrofit 会直接省略，
 * 所以合成一个方法，调用方只传要改的项。
 */
interface SignApi {

    @FormUrlEncoded
    @POST("/api/v2/account/edit")
    suspend fun edit(
        @Header("Authorization") token: String,
        @Field("new_mail_address") newMailAddress: String?,
        @Field("new_user_account") newUserAccount: String?,
        @Field("current_password") currentPassword: String,
        @Field("new_password") newPassword: String?,
    ): AccountEditResponse

    companion object {
        const val SIGN_API = "https://accounts.pixiv.net/"
    }
}
