package ceui.pixiv.ui.upscale

enum class UpscaleModel(
    val displayName: String,
    val description: String,
    val executableName: String,
    val assetDir: String,
    val extractDir: String,
    val modelFiles: List<String>,
    val extraArgs: List<String>
) {
    REAL_ESRGAN(
        displayName = "Real-ESRGAN",
        description = "通用动漫超分，速度快",
        executableName = "librealsr_ncnn.so",
        assetDir = "Real-ESRGANv3-anime",
        extractDir = "models-Real-ESRGANv3-anime",
        modelFiles = listOf("x2.bin", "x2.param"),
        extraArgs = emptyList()
    ),
    REAL_CUGAN(
        displayName = "Real-CUGAN",
        description = "B站动漫专用，线稿更锐利",
        executableName = "librealcugan_ncnn.so",
        assetDir = "Real-CUGAN-pro",
        extractDir = "models-pro",
        modelFiles = listOf("up2x-conservative.bin", "up2x-conservative.param"),
        extraArgs = listOf("-n", "-1", "-s", "2")
    );
}
