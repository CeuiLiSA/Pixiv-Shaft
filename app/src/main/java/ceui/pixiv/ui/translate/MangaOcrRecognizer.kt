package ceui.pixiv.ui.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * manga-ocr recognizer using ONNX Runtime.
 *
 * Architecture: ViT encoder (DeiT-base 384x384) + GPT-2 decoder.
 * Specialized for Japanese manga text — much more accurate than generic OCR.
 *
 * Usage:
 *   MangaOcrRecognizer.loadModel(context, MangaOcrModel.MANGA_OCR_BASE)
 *   val text = MangaOcrRecognizer.recognize(croppedBitmap)
 */
object MangaOcrRecognizer {

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var vocab: List<String>? = null
    private var config: OcrConfig? = null
    private var ortEnv: OrtEnvironment? = null

    private data class OcrConfig(
        val encoderFile: String,
        val decoderFile: String,
        val vocabFile: String,
        val imageSize: Int,
        val imageMean: FloatArray,
        val imageStd: FloatArray,
        val bosTokenId: Int,
        val eosTokenId: Int,
        val padTokenId: Int,
        val vocabSize: Int,
        val maxLength: Int,
    )

    val isLoaded: Boolean get() = encoderSession != null && decoderSession != null

    @Synchronized
    fun loadModel(context: Context, model: MangaOcrModel) {
        if (isLoaded) return

        val modelDir = MangaOcrModelManager.modelDir(context, model)
        val configFile = File(modelDir, "config.json")
        val json = JSONObject(configFile.readText())

        val meanArr = json.getJSONArray("image_mean")
        val stdArr = json.getJSONArray("image_std")

        val cfg = OcrConfig(
            encoderFile = json.getString("encoder_file"),
            decoderFile = json.getString("decoder_file"),
            vocabFile = json.getString("vocab_file"),
            imageSize = json.getInt("image_size"),
            imageMean = FloatArray(meanArr.length()) { meanArr.getDouble(it).toFloat() },
            imageStd = FloatArray(stdArr.length()) { stdArr.getDouble(it).toFloat() },
            bosTokenId = json.getInt("bos_token_id"),
            eosTokenId = json.getInt("eos_token_id"),
            padTokenId = json.optInt("pad_token_id", 0),
            vocabSize = json.getInt("vocab_size"),
            maxLength = json.optInt("max_length", 300),
        )
        config = cfg

        val env = OrtEnvironment.getEnvironment()
        ortEnv = env

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }

        Timber.d("MangaOcr: loading encoder from ${cfg.encoderFile}")
        encoderSession = env.createSession(
            File(modelDir, cfg.encoderFile).absolutePath, opts
        )

        Timber.d("MangaOcr: loading decoder from ${cfg.decoderFile}")
        decoderSession = env.createSession(
            File(modelDir, cfg.decoderFile).absolutePath, opts
        )

        // Load vocabulary
        val vocabFile = File(modelDir, cfg.vocabFile)
        val vocabArr = JSONArray(vocabFile.readText())
        val vocabList = mutableListOf<String>()
        for (i in 0 until vocabArr.length()) {
            vocabList.add(vocabArr.getString(i))
        }
        vocab = vocabList

        Timber.d("MangaOcr: model loaded, vocab size=${vocabList.size}")
    }

    @Synchronized
    fun unloadModel() {
        encoderSession?.close()
        decoderSession?.close()
        encoderSession = null
        decoderSession = null
        vocab = null
        config = null
        Timber.d("MangaOcr: model unloaded")
    }

    /**
     * Recognize text in a cropped text region bitmap.
     *
     * @param bitmap Cropped image of a single text region
     * @return Recognized Japanese text
     */
    fun recognize(bitmap: Bitmap): String {
        val encoder = encoderSession ?: throw IllegalStateException("Model not loaded")
        val decoder = decoderSession ?: throw IllegalStateException("Model not loaded")
        val cfg = config ?: throw IllegalStateException("Model not loaded")
        val vocabList = vocab ?: throw IllegalStateException("Model not loaded")
        val env = ortEnv ?: throw IllegalStateException("Model not loaded")

        // Step 1: Preprocess image → [1, 3, H, W] float tensor
        val pixelValues = preprocessImage(bitmap, cfg)
        val pixelTensor = OnnxTensor.createTensor(
            env, pixelValues,
            longArrayOf(1, 3, cfg.imageSize.toLong(), cfg.imageSize.toLong())
        )

        // Step 2: Run encoder
        val encoderResult = encoder.run(mapOf("pixel_values" to pixelTensor))
        val encoderHidden = encoderResult[0] as OnnxTensor

        // Step 3: Autoregressive decoding
        val decodedTokens = mutableListOf<Int>()
        var decoderInputIds = longArrayOf(cfg.bosTokenId.toLong())

        for (step in 0 until cfg.maxLength) {
            val decInputTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(decoderInputIds),
                longArrayOf(1, decoderInputIds.size.toLong())
            )

            val decoderResult = decoder.run(mapOf(
                "input_ids" to decInputTensor,
                "encoder_hidden_states" to encoderHidden,
            ))

            val logitsTensor = decoderResult[0] as OnnxTensor
            val logitsShape = logitsTensor.info.shape
            val vocabSize = logitsShape[2].toInt()
            val seqLen = logitsShape[1].toInt()
            val logitsBuffer = logitsTensor.floatBuffer

            // Argmax for last position
            val offset = (seqLen - 1) * vocabSize
            var maxVal = Float.NEGATIVE_INFINITY
            var maxIdx = 0
            for (v in 0 until vocabSize) {
                val value = logitsBuffer.get(offset + v)
                if (value > maxVal) {
                    maxVal = value
                    maxIdx = v
                }
            }

            decoderResult.close()

            if (maxIdx == cfg.eosTokenId) break

            decodedTokens.add(maxIdx)
            decoderInputIds = decoderInputIds + maxIdx.toLong()
        }

        // Cleanup
        encoderResult.close()
        pixelTensor.close()

        // Step 4: Decode tokens to text, filtering out special tokens
        val specialTokenIds = setOf(cfg.bosTokenId, cfg.eosTokenId, cfg.padTokenId)
        val specialTokens = setOf("[CLS]", "[SEP]", "[PAD]", "[UNK]", "[MASK]")
        val sb = StringBuilder()
        for (tokenId in decodedTokens) {
            if (tokenId in specialTokenIds) continue
            if (tokenId >= 0 && tokenId < vocabList.size) {
                val token = vocabList[tokenId]
                if (token !in specialTokens) {
                    sb.append(token)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Preprocess image: resize to imageSize x imageSize, normalize with ImageNet mean/std.
     * Returns a FloatBuffer in CHW format.
     */
    private fun preprocessImage(bitmap: Bitmap, cfg: OcrConfig): FloatBuffer {
        val size = cfg.imageSize
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)

        val buffer = FloatBuffer.allocate(3 * size * size)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled != bitmap) scaled.recycle()

        // CHW format, normalized
        val mean = cfg.imageMean
        val std = cfg.imageStd

        // R channel
        for (pixel in pixels) {
            buffer.put((Color.red(pixel) / 255f - mean[0]) / std[0])
        }
        // G channel
        for (pixel in pixels) {
            buffer.put((Color.green(pixel) / 255f - mean[1]) / std[1])
        }
        // B channel
        for (pixel in pixels) {
            buffer.put((Color.blue(pixel) / 255f - mean[2]) / std[2])
        }

        buffer.rewind()
        return buffer
    }
}
