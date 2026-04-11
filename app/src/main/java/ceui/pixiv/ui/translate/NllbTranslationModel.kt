package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.DownloadableModel

enum class NllbTranslationModel(
    override val displayName: String,
    override val description: String,
    override val assetDir: String,
    override val modelFiles: List<String>,
    override val sizeLabel: String,
    override val downloadUrl: String? = null,
) : DownloadableModel {
    NLLB_600M(
        displayName = "NLLB-600M",
        description = "Meta AI 多语言翻译模型，600M 参数，漫画口语翻译质量远超 Opus-MT",
        assetDir = "nllb-600m",
        modelFiles = listOf(
            "config.json",
            "encoder_model_quantized.onnx",
            "decoder_model_quantized.onnx",
            "tokenizer.json",
        ),
        sizeLabel = "829MB",
        downloadUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/nllb-600m-int8.zip",
    );
}
