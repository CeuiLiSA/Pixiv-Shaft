package ceui.pixiv.ui.common

interface DownloadableModel {
    val displayName: String
    val description: String
    val assetDir: String
    val modelFiles: List<String>
    val sizeLabel: String
    val downloadUrl: String?
}
