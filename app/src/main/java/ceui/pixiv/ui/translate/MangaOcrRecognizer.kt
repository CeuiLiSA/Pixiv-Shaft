package ceui.pixiv.ui.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.ln

/**
 * manga-ocr recognizer using ONNX Runtime.
 *
 * Architecture: ViT encoder (DeiT-base 384x384) + GPT-2 decoder.
 * Specialized for Japanese manga text — much more accurate than generic OCR.
 *
 * 普通 class:一份会话就是一组已加载的 ORT session,由 [MangaTranslateModels] 持有进程内共用的
 * 那份(模型加载慢、占内存,批量翻译与单页翻译不该各开一份)。
 *
 * Usage:
 *   val ocr = MangaOcrRecognizer()
 *   ocr.loadModel(context, MangaOcrModel.MANGA_OCR_BASE)
 *   val text = ocr.recognize(croppedBitmap)
 *   ocr.release()
 *
 * 线程模型:[loadModel] / [release] 拿写锁,[recognize] 拿读锁 —— release 不会在一次识别
 * 中途把 session 关掉,并发 recognize 之间互不阻塞(ORT session 本身可并发 run)。
 */
/**
 * manga-ocr 重识别结果。
 *
 * @property text 解码后的文字
 * @property confidence 每 token softmax 最大概率的几何平均(exp(平均 logP))。
 *   正经文本 0.6-0.95;模型在噪声 crop 上 hallucinate 通常 < 0.3
 */
data class OcrResult(val text: String, val confidence: Float)

class MangaOcrRecognizer {

    private val lock = ReentrantReadWriteLock()

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

    val isLoaded: Boolean get() = lock.read { encoderSession != null && decoderSession != null }

    fun loadModel(context: Context, model: MangaOcrModel) = lock.write {
        if (encoderSession != null && decoderSession != null) return@write

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

    /** 关掉 ORT session 释放内存;之后再 [loadModel] 可重新加载。没加载过则无操作。 */
    /**
     * 释放模型会话。`tryLock` 而不是阻塞拿写锁：它由 Application.onTrimMemory 在主线程调，
     * 正有识别在跑时宁可这次不释放，也不能让主线程等一次 ORT 推理。
     *
     * @return false = 正在识别、本次跳过。
     */
    fun release(): Boolean {
        val w = lock.writeLock()
        if (!w.tryLock()) return false
        try {
            if (encoderSession == null && decoderSession == null) return true
            encoderSession?.close()
            decoderSession?.close()
            encoderSession = null
            decoderSession = null
            vocab = null
            config = null
            Timber.d("MangaOcr: model unloaded")
            return true
        } finally {
            w.unlock()
        }
    }

    /**
     * Recognize text in a cropped text region bitmap.
     *
     * @param bitmap Cropped image of a single text region
     * @return Recognized Japanese text + 模型自身置信度
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult {
        coroutineContext.ensureActive()
        return lock.read { recognizeLocked(bitmap) }
    }

    private suspend fun recognizeLocked(bitmap: Bitmap): OcrResult {
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

        // Step 3: Autoregressive decoding + per-token log P 累计
        val decodedTokens = mutableListOf<Int>()
        var decoderInputIds = longArrayOf(cfg.bosTokenId.toLong())
        // 用 Double 累加避免 float 误差堆积;eos token 不计入(它只是终止信号)
        var totalLogProb = 0.0
        var counted = 0

        try {
            for (step in 0 until cfg.maxLength) {
                // 自回归解码是逐 token 阻塞的,每步检查一次取消:
                // 页面销毁后最多再跑一步 decoder,而不是等整页 OCR 全部跑完
                coroutineContext.ensureActive()
                val decInputTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(decoderInputIds),
                    longArrayOf(1, decoderInputIds.size.toLong())
                )

                val stepResult = try {
                    val decoderResult = decoder.run(mapOf(
                        "input_ids" to decInputTensor,
                        "encoder_hidden_states" to encoderHidden,
                    ))
                    try {
                        val logitsTensor = decoderResult[0] as OnnxTensor
                        val logitsShape = logitsTensor.info.shape
                        val vocabSize = logitsShape[2].toInt()
                        val seqLen = logitsShape[1].toInt()
                        val logitsBuffer = logitsTensor.floatBuffer
                        val offset = (seqLen - 1) * vocabSize

                        // Argmax + numerically stable log-sum-exp
                        var maxVal = Float.NEGATIVE_INFINITY
                        var idx = 0
                        for (v in 0 until vocabSize) {
                            val value = logitsBuffer.get(offset + v)
                            if (value > maxVal) {
                                maxVal = value
                                idx = v
                            }
                        }
                        // log P(idx) = maxVal - log(sum exp(logits_v))
                        //            = -log(sum exp(logits_v - maxVal))
                        var shiftedSum = 0.0
                        for (v in 0 until vocabSize) {
                            shiftedSum += exp((logitsBuffer.get(offset + v) - maxVal).toDouble())
                        }
                        val logProb = -ln(shiftedSum)
                        idx to logProb
                    } finally {
                        decoderResult.close()
                    }
                } finally {
                    decInputTensor.close()
                }

                val maxIdx = stepResult.first
                val logProb = stepResult.second
                if (maxIdx == cfg.eosTokenId) break

                decodedTokens.add(maxIdx)
                totalLogProb += logProb
                counted++
                decoderInputIds = decoderInputIds + maxIdx.toLong()
            }
        } finally {
            encoderResult.close()
            pixelTensor.close()
        }

        val confidence: Float = if (counted == 0) 0f else exp(totalLogProb / counted).toFloat()

        // Step 4: Decode tokens to text. 兼容两种 HF tokenizer:
        //   - WordPiece (BertTokenizer,manga-ocr 实际用的):"##" 前缀去掉,直接拼接
        //   - SentencePiece:"▁" (U+2581) 表示词边界 — 日文里几乎不出现,出现就直接 strip
        //   - 整段 NFKC normalize 对齐 HF post-process
        val specialTokenIds = setOf(cfg.bosTokenId, cfg.eosTokenId, cfg.padTokenId)
        val specialTokens = setOf("[CLS]", "[SEP]", "[PAD]", "[UNK]", "[MASK]")
        val sb = StringBuilder()
        for (tokenId in decodedTokens) {
            if (tokenId in specialTokenIds) continue
            if (tokenId !in 0 until vocabList.size) continue
            var token = vocabList[tokenId]
            if (token in specialTokens) continue
            token = when {
                token.startsWith("##") -> token.substring(2)
                token.startsWith("▁") -> token.substring(1)
                else -> token
            }
            sb.append(token)
        }
        val text = Normalizer.normalize(sb, Normalizer.Form.NFKC)
        return OcrResult(text, confidence)
    }

    /**
     * Preprocess image to match upstream kha-white/manga-ocr training distribution:
     *   PIL Image.convert('L').convert('RGB')  →  BT.601 luma 复制到三通道
     * 然后 ImageNet 风格 mean/std 归一化,CHW 排布。
     */
    private fun preprocessImage(bitmap: Bitmap, cfg: OcrConfig): FloatBuffer {
        val size = cfg.imageSize
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)

        val buffer = FloatBuffer.allocate(3 * size * size)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled != bitmap) scaled.recycle()

        // BT.601 luma:Image.convert('L') 用的就是这个权重
        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            luma[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        val mean = cfg.imageMean
        val std = cfg.imageStd
        // CHW:三通道相同(灰度扩展)
        for (v in luma) buffer.put((v - mean[0]) / std[0])
        for (v in luma) buffer.put((v - mean[1]) / std[1])
        for (v in luma) buffer.put((v - mean[2]) / std[2])

        buffer.rewind()
        return buffer
    }
}
