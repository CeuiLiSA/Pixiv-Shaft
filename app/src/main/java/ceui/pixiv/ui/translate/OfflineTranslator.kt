package ceui.pixiv.ui.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext

/**
 * Offline Japanese → Chinese translator using ONNX Runtime + Opus-MT.
 *
 * Architecture: MarianMT encoder-decoder with autoregressive decoding.
 * Model files are loaded on first use and cached in memory.
 */
object OfflineTranslator {

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var sourceTokenizer: SentencePieceProcessor? = null
    private var targetTokenizer: SentencePieceProcessor? = null
    private var config: ModelConfig? = null
    private var ortEnv: OrtEnvironment? = null

    private data class ModelConfig(
        val encoderFile: String,
        val decoderFile: String,
        val sourceVocabFile: String,
        val targetVocabFile: String,
        val vocabSize: Int,
        val decoderStartTokenId: Int,
        val eosTokenId: Int,
        val padTokenId: Int,
        val maxLength: Int,
    )

    val isLoaded: Boolean get() = encoderSession != null && decoderSession != null

    /**
     * Load model from disk. Call this after model is downloaded.
     * Thread-safe: can be called from any thread.
     */
    @Synchronized
    fun loadModel(context: Context, model: TranslationModel) {
        if (isLoaded) return

        val modelDir = TranslationModelManager.modelDir(context, model)
        val configFile = File(modelDir, "model_config.json")
        val json = JSONObject(configFile.readText())

        val cfg = ModelConfig(
            encoderFile = json.getString("encoder_file"),
            decoderFile = json.getString("decoder_file"),
            sourceVocabFile = json.getString("source_vocab_file"),
            targetVocabFile = json.getString("target_vocab_file"),
            vocabSize = json.getInt("vocab_size"),
            decoderStartTokenId = json.optInt("decoder_start_token_id", 65001),
            eosTokenId = json.optInt("eos_token_id", 0),
            padTokenId = json.optInt("pad_token_id", 65001),
            maxLength = json.optInt("max_length", 512),
        )
        config = cfg

        val env = OrtEnvironment.getEnvironment()
        ortEnv = env

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }

        Timber.d("Translator: loading encoder from ${cfg.encoderFile}")
        encoderSession = env.createSession(
            File(modelDir, cfg.encoderFile).absolutePath, opts
        )

        Timber.d("Translator: loading decoder from ${cfg.decoderFile}")
        decoderSession = env.createSession(
            File(modelDir, cfg.decoderFile).absolutePath, opts
        )

        val srcTokenizer = SentencePieceProcessor()
        srcTokenizer.load(File(modelDir, cfg.sourceVocabFile))
        sourceTokenizer = srcTokenizer

        val tgtTokenizer = SentencePieceProcessor()
        tgtTokenizer.load(File(modelDir, cfg.targetVocabFile))
        targetTokenizer = tgtTokenizer

        Timber.d("Translator: model loaded successfully")
    }

    @Synchronized
    fun unloadModel() {
        encoderSession?.close()
        decoderSession?.close()
        encoderSession = null
        decoderSession = null
        sourceTokenizer = null
        targetTokenizer = null
        config = null
        Timber.d("Translator: model unloaded")
    }

    /**
     * Translate Japanese text to Chinese.
     * Splits long text into sentences and translates each.
     */
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val encoder = encoderSession ?: throw IllegalStateException("Model not loaded")
        val decoder = decoderSession ?: throw IllegalStateException("Model not loaded")
        val srcSP = sourceTokenizer ?: throw IllegalStateException("Model not loaded")
        val tgtSP = targetTokenizer ?: throw IllegalStateException("Model not loaded")
        val cfg = config ?: throw IllegalStateException("Model not loaded")
        val env = ortEnv ?: throw IllegalStateException("Model not loaded")

        // Split by sentence boundaries for long text
        val sentences = splitSentences(text)
        val results = mutableListOf<String>()

        for (sentence in sentences) {
            coroutineContext.ensureActive()

            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) {
                results.add("")
                continue
            }

            val translated = translateSentence(env, encoder, decoder, srcSP, tgtSP, cfg, trimmed)
            results.add(translated)
        }

        results.joinToString("")
    }

    private fun translateSentence(
        env: OrtEnvironment,
        encoder: OrtSession,
        decoder: OrtSession,
        srcSP: SentencePieceProcessor,
        tgtSP: SentencePieceProcessor,
        cfg: ModelConfig,
        text: String
    ): String {
        // Step 1: Tokenize source text
        val tokenIds = srcSP.encode(text)
        // Append EOS token
        val inputIds = (tokenIds + cfg.eosTokenId).map { it.toLong() }.toLongArray()
        val attentionMask = LongArray(inputIds.size) { 1L }
        val seqLen = inputIds.size.toLong()

        // Step 2: Run encoder
        val inputIdsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen)
        )
        val attMaskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen)
        )

        val encoderInputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attMaskTensor,
        )

        val encoderResult = encoder.run(encoderInputs)
        val encoderHiddenStates = encoderResult[0] as OnnxTensor

        // Step 3: Autoregressive decoding
        val decodedTokens = mutableListOf<Int>()
        var decoderInputIds = longArrayOf(cfg.decoderStartTokenId.toLong())
        val maxDecodeSteps = minOf(cfg.maxLength, inputIds.size * 3) // reasonable limit

        for (step in 0 until maxDecodeSteps) {
            val decInputTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(decoderInputIds),
                longArrayOf(1, decoderInputIds.size.toLong())
            )

            val decoderInputs = mapOf(
                "input_ids" to decInputTensor,
                "encoder_hidden_states" to encoderHiddenStates,
                "encoder_attention_mask" to attMaskTensor,
            )

            val decoderResult = decoder.run(decoderInputs)
            val logitsTensor = decoderResult[0] as OnnxTensor

            // Get logits for the last token position
            // Shape: [1, decoder_seq_len, vocab_size]
            val logitsShape = logitsTensor.info.shape
            val vocabSize = logitsShape[2].toInt()
            val decoderSeqLen = logitsShape[1].toInt()
            val logitsBuffer = logitsTensor.floatBuffer

            // Find argmax for the last position
            val offset = (decoderSeqLen - 1) * vocabSize
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

            // Check EOS
            if (maxIdx == cfg.eosTokenId) break

            decodedTokens.add(maxIdx)

            // Extend decoder input for next step
            decoderInputIds = decoderInputIds + maxIdx.toLong()
        }

        // Cleanup encoder tensors
        encoderResult.close()
        inputIdsTensor.close()

        // Step 4: Decode tokens to text
        return tgtSP.decode(decodedTokens)
    }

    private fun splitSentences(text: String): List<String> {
        // Split on Japanese/Chinese sentence boundaries, keeping delimiters
        val result = mutableListOf<String>()
        val sb = StringBuilder()

        for (ch in text) {
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '\n' ||
                ch == '.' || ch == '!' || ch == '?'
            ) {
                result.add(sb.toString())
                sb.clear()
            }
        }

        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }

        return result
    }
}
