package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.DownloadableModel

enum class SakuraModel(
    override val displayName: String,
    override val description: String,
    override val assetDir: String,
    override val modelFiles: List<String>,
    override val sizeLabel: String,
    override val downloadUrl: String? = null,
) : DownloadableModel {
    SAKURA_1_5B(
        displayName = "Sakura-1.5B",
        description = "ACG 专用日→中翻译模型，轻小说/漫画/Galgame 翻译质量极高",
        assetDir = "sakura-1.5b",
        modelFiles = listOf("sakura-1.5b-q3_k_m.gguf"),
        sizeLabel = "876MB",
        downloadUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/sakura-1.5b-q3_k_m.gguf",
    );
}
