package ceui.pixiv.shaftapi

/** Transport reports a reason; the UI supplies localized copy. IOException is safe in writeTo. */
internal class MediaUploadException(val reason: Reason, val httpStatus: Int? = null) :
    java.io.IOException(reason.name) {
    enum class Reason {
        TYPE,
        SIZE,
        SIZE_UNKNOWN,
        READ,
        BOUNDS,
        METHOD,
        CHANGED,
        HTTP,
        DIMENSIONS,
    }
}
