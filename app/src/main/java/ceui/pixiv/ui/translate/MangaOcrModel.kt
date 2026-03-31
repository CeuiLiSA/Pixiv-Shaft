package ceui.pixiv.ui.translate

import ceui.pixiv.ui.common.DownloadableModel

enum class MangaOcrModel(
    override val displayName: String,
    override val description: String,
    override val assetDir: String,
    override val modelFiles: List<String>,
    override val sizeLabel: String,
    override val downloadUrl: String? = null,
) : DownloadableModel {
    MANGA_OCR_BASE(
        displayName = "Manga-OCR",
        description = "漫画日文专用 OCR 模型，ViT+GPT2，识别精度远超通用 OCR",
        assetDir = "manga-ocr-base",
        modelFiles = listOf(
            "config.json",
            "encoder_model_quantized.onnx",
            "decoder_model_quantized.onnx",
            "vocab.json",
        ),
        sizeLabel = "91MB",
        downloadUrl = "https://github.com/CeuiLiSA/Pixiv-Shaft/releases/download/v4.5.1/manga-ocr-base-int8.zip",
    );
}
