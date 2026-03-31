package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.DownloadableModel

enum class TranslationModel(
    override val displayName: String,
    override val description: String,
    override val assetDir: String,
    override val modelFiles: List<String>,
    override val sizeLabel: String,
    override val downloadUrl: String? = null,
) : DownloadableModel {
    OPUS_MT_JA_ZH(
        displayName = "Opus-MT ja→zh",
        description = "Helsinki-NLP 日文→中文翻译模型，int8 量化，离线推理",
        assetDir = "opus-mt-ja-zh",
        modelFiles = listOf(
            "model_config.json",
            "encoder_model_quantized.onnx",
            "decoder_model_quantized.onnx",
            "source_vocab.json",
            "target_vocab.json",
        ),
        sizeLabel = "87MB",
        downloadUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/opus-mt-ja-zh-int8.zip",
    );
}
