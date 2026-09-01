package ceui.pixiv.api.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class IdpUrlsResponse(
    @SerializedName("account-edit") val accountEdit: String? = null,
    @SerializedName("account-leave-prepare") val accountLeavePrepare: String? = null,
    @SerializedName("account-leave-status") val accountLeaveStatus: String? = null,
    @SerializedName("account-setting-prepare") val accountSettingPrepare: String? = null,
    @SerializedName("auth-token") val authToken: String? = null,
    @SerializedName("auth-token-redirect-uri") val authTokenRedirectUri: String? = null,
) : Serializable
