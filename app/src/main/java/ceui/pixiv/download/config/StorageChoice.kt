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

    /**
     * 这个存储位置给「下载类」产物（小说 / 简介 / 备份 / 日志）用时应落在哪：
     * 相册卷 Pictures 只收 image 类 MIME，MediaStore 会直接拒掉 text/plain、application/json
     * 的 insert，所以 Images 一律换成 Downloads；SAF / AppCache / 本来就是 Downloads 则原样。
     * [ceui.pixiv.download.DownloadsRegistry.applyGlobalStorage] 与 [DownloadConfig.resolve]
     * 共用这一条规则。
     */
    fun forDownloadsBucket(): StorageChoice = when (this) {
        is MediaStore -> if (collection == MediaStore.Collection.Images) MediaStore(MediaStore.Collection.Downloads) else this
        is Saf, AppCache -> this
    }
}
