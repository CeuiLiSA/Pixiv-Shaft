package ceui.pixiv.download.config

/**
 * What a backend should do when the target path already exists. Kept here (not
 * in the backend package) because the decision is a user-config concern; each
 * backend honours it but does not define it.
 */
enum class OverwritePolicy {
    /** Reuse the existing file, skip the write. */
    Skip,

    /** Delete the existing file and write a fresh one. */
    Replace,

    /** Append ` (N)` before the extension until a free name is found. */
    Rename,
}
