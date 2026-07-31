package ceui.pixiv.ui.interpolate

import ceui.pixiv.ui.common.DownloadableModel

enum class RifeModel(
    override val displayName: String,
    override val description: String,
    override val assetDir: String,
    override val modelFiles: List<String>,
    override val sizeLabel: String,
    override val downloadUrl: String? = null,
) : DownloadableModel {
    RIFE_V4_6(
        displayName = "RIFE v4.6",
        description = "动图(ugoira)补帧专用光流模型,nihui/rife-ncnn-vulkan,在相邻帧之间插入 AI 生成的中间帧,帧率翻倍",
        assetDir = "rife-v4.6",
        modelFiles = listOf("flownet.param", "flownet.bin"),
        sizeLabel = "11MB",
        downloadUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/rife-v4.6.zip",
    );
}
