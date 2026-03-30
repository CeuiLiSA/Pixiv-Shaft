package ceui.pixiv.ui.upscale

enum class RembgModel(
    val displayName: String,
    val description: String,
    val profileArg: String,
    val assetDir: String,
    val modelFiles: List<String>,
    val sizeLabel: String,
    val downloadUrl: String? = null,
    val bundledInApk: Boolean = true
) {
    U2NETP(
        displayName = "U2Net-P",
        description = "通用快速抠图，体积小",
        profileArg = "u2netp",
        assetDir = "u2netp",
        modelFiles = listOf("u2netp.param", "u2netp.bin"),
        sizeLabel = "4MB"
    ),
    ISNET_ANIME(
        displayName = "ISNet-Anime",
        description = "动漫角色专用，Danbooru 训练，效果最佳",
        profileArg = "isnet-anime",
        assetDir = "isnet-anime",
        modelFiles = listOf("isnet-anime.param", "isnet-anime.bin"),
        sizeLabel = "84MB",
        downloadUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/isnet-anime.zip",
        bundledInApk = false
    );
}
