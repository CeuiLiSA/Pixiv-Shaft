package ceui.pixiv.ui.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext

/**
 * Offline Japanese → Chinese translator using NLLB-200-distilled-600M on ONNX Runtime.
 *
 * Much higher quality than Opus-MT, especially for manga dialogue,
 * colloquial speech, and onomatopoeia.
 */
object NllbTranslator {

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: BpeTokenizer? = null
    private var config: NllbConfig? = null
    private var ortEnv: OrtEnvironment? = null

    private data class NllbConfig(
        val encoderFile: String,
        val decoderFile: String,
        val vocabFile: String,
        val spmVocabFile: String?,
        val sourceLangId: Int,
        val targetLangId: Int,
        val eosTokenId: Int,
        val padTokenId: Int,
        val bosTokenId: Int,
        val vocabSize: Int,
        val maxLength: Int,
    )

    val isLoaded: Boolean get() = encoderSession != null && decoderSession != null

    @Synchronized
    fun loadModel(context: Context, model: NllbTranslationModel) {
        if (isLoaded) return

        val modelDir = NllbModelManager.modelDir(context, model)
        val configFile = File(modelDir, "config.json")
        val json = JSONObject(configFile.readText())

        val cfg = NllbConfig(
            encoderFile = json.getString("encoder_file"),
            decoderFile = json.getString("decoder_file"),
            vocabFile = json.getString("vocab_file"),
            spmVocabFile = json.optString("spm_vocab_file", null),
            sourceLangId = json.getInt("source_lang_id"),
            targetLangId = json.getInt("target_lang_id"),
            eosTokenId = json.getInt("eos_token_id"),
            padTokenId = json.getInt("pad_token_id"),
            bosTokenId = json.optInt("bos_token_id", 0),
            vocabSize = json.getInt("vocab_size"),
            maxLength = json.optInt("max_length", 256),
        )
        config = cfg

        val env = OrtEnvironment.getEnvironment()
        ortEnv = env

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }

        Timber.d("NLLB: loading encoder from ${cfg.encoderFile}")
        encoderSession = env.createSession(
            File(modelDir, cfg.encoderFile).absolutePath, opts
        )

        Timber.d("NLLB: loading decoder from ${cfg.decoderFile}")
        decoderSession = env.createSession(
            File(modelDir, cfg.decoderFile).absolutePath, opts
        )

        // Load BPE tokenizer from tokenizer.json
        val tokenizerFile = File(modelDir, "tokenizer.json")
        val bpe = BpeTokenizer()
        bpe.load(tokenizerFile)
        tokenizer = bpe

        Timber.d("NLLB: model loaded, vocab size=${bpe.vocabSize}")
    }

    @Synchronized
    fun unloadModel() {
        encoderSession?.close()
        decoderSession?.close()
        encoderSession = null
        decoderSession = null
        tokenizer = null
        config = null
        Timber.d("NLLB: model unloaded")
    }

    /**
     * Translate Japanese text to Chinese.
     */
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val encoder = encoderSession ?: throw IllegalStateException("Model not loaded")
        val decoder = decoderSession ?: throw IllegalStateException("Model not loaded")
        val bpe = tokenizer ?: throw IllegalStateException("Model not loaded")
        val cfg = config ?: throw IllegalStateException("Model not loaded")
        val env = ortEnv ?: throw IllegalStateException("Model not loaded")

        val sentences = splitSentences(text)
        val results = mutableListOf<String>()

        for (sentence in sentences) {
            coroutineContext.ensureActive()
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) {
                results.add("")
                continue
            }
            val translated = translateSentence(env, encoder, decoder, bpe, cfg, trimmed)
            results.add(translated)
        }

        results.joinToString("")
    }

    private fun translateSentence(
        env: OrtEnvironment,
        encoder: OrtSession,
        decoder: OrtSession,
        bpe: BpeTokenizer,
        cfg: NllbConfig,
        text: String
    ): String {
        // Step 1: Tokenize — NLLB format: <src_lang> <tokens> </s>
        val tokenIds = bpe.encode(text)
        val inputIds = (listOf(cfg.sourceLangId) + tokenIds + cfg.eosTokenId)
            .map { it.toLong() }.toLongArray()
        val attentionMask = LongArray(inputIds.size) { 1L }
        val seqLen = inputIds.size.toLong()
        Timber.d("NLLB encode: \"%s\" → tokenIds=%s → inputIds=%s",
            text.take(30), tokenIds.take(10).toString(), inputIds.toList().toString())

        // Step 2: Run encoder
        val inputIdsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen)
        )
        val attMaskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen)
        )

        val encoderResult = encoder.run(mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attMaskTensor,
        ))
        val encoderHiddenStates = encoderResult[0] as OnnxTensor

        // Step 3: Autoregressive decoding — start with [eos, target_lang] like model.generate()
        val decodedTokens = mutableListOf<Int>()
        var decoderInputIds = longArrayOf(cfg.eosTokenId.toLong(), cfg.targetLangId.toLong())
        val maxDecodeSteps = minOf(cfg.maxLength, inputIds.size * 3)

        for (step in 0 until maxDecodeSteps) {
            val decInputTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(decoderInputIds),
                longArrayOf(1, decoderInputIds.size.toLong())
            )

            val decoderResult = decoder.run(mapOf(
                "input_ids" to decInputTensor,
                "encoder_hidden_states" to encoderHiddenStates,
                "encoder_attention_mask" to attMaskTensor,
            ))

            val logitsTensor = decoderResult[0] as OnnxTensor
            val logitsShape = logitsTensor.info.shape
            val vocabSize = logitsShape[2].toInt()
            val decoderSeqLen = logitsShape[1].toInt()
            val logitsBuffer = logitsTensor.floatBuffer

            // Apply repetition penalty then argmax for last position
            val offset = (decoderSeqLen - 1) * vocabSize
            val REP_PENALTY = 1.2f
            // Copy logits for last position so we can modify them
            val logits = FloatArray(vocabSize)
            for (v in 0 until vocabSize) {
                logits[v] = logitsBuffer.get(offset + v)
            }
            // Penalize already-generated tokens
            for (prevId in decodedTokens) {
                if (prevId in 0 until vocabSize) {
                    if (logits[prevId] > 0) {
                        logits[prevId] /= REP_PENALTY
                    } else {
                        logits[prevId] *= REP_PENALTY
                    }
                }
            }
            var maxVal = Float.NEGATIVE_INFINITY
            var maxIdx = 0
            for (v in 0 until vocabSize) {
                if (logits[v] > maxVal) {
                    maxVal = logits[v]
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
        inputIdsTensor.close()

        // Step 4: Decode tokens
        return bpe.decode(decodedTokens)
    }

    private fun splitSentences(text: String): List<String> {
        // Don't split short text
        if (text.length <= 60) return listOf(text)

        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '\n') {
                result.add(sb.toString())
                sb.clear()
            } else if (ch == '!' || ch == '?' || ch == '.') {
                // Consume consecutive punctuation (e.g. "..." "!!" "?!")
                while (i + 1 < text.length && text[i + 1] in ".!?") {
                    i++
                    sb.append(text[i])
                }
                result.add(sb.toString())
                sb.clear()
            }
            i++
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }
}
