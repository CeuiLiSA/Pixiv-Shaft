package ceui.pixiv.download.config

import android.net.Uri

/**
 * User-selected backend kind + the extra data each kind needs.
 * Sealed so `when` is exhaustive — no default branch hiding silent failures.
 */
sealed interface StorageChoice {

    data class MediaStore(val collection: Collection) : StorageChoice {
        enum class Collection { Images, Downloads }
    }

    data class Saf(val treeUri: Uri) : StorageChoice

    data object AppCache : StorageChoice
}
