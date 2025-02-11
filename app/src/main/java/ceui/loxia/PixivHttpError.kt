package ceui.loxia

data class PixivHttpError(
    val user_message: String? = null,
    val message: String? = null,
    val reason: String? = null,
)

data class ErrorResp(
    val error: PixivHttpError? = null
)

