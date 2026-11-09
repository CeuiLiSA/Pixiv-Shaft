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
        // realsr 二进制 -s 默认 4，会去找不存在的 x4.param/x4.bin，输出红黄条纹乱图(#861)；
        // 必须显式传 -s 2 匹配 bundled 的 x2 模型，与 Real-CUGAN 一致。
        extraArgs = listOf("-s", "2")
    ),
    REAL_CUGAN(
        displayName = "Real-CUGAN",
        description = "B站动漫专用，线稿更锐利",
        executableName = "librealcugan_ncnn.so",
        assetDir = "Real-CUGAN-pro",
        extractDir = "models-pro",
        modelFiles = listOf("up2x-no-denoise.bin", "up2x-no-denoise.param"),
        // 1. 显式传 -n 0 (no-denoise)：Real-CUGAN Pro 的 conservative (-n -1) 模式存在上游模型训练残差振荡，
        //    在高对比色差描边处（如深色/红色过渡区）会产生孤立品红/青色噪点；no-denoise 模式保留极高锐度的同时彻底消除彩噪。
        // 2. 传 -c 1 (精确 GAP 全局同步) 与 -s 2 匹配 2x 模型。
        extraArgs = listOf("-n", "0", "-s", "2", "-c", "1")
    );
}
