package ceui.pixiv.ui.translate

import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * BPE tokenizer that parses HuggingFace tokenizer.json format.
 * Used for NLLB-200 translation model.
 */
class BpeTokenizer {

    private var vocab: Map<String, Int> = emptyMap()
    private var idToToken: Map<Int, String> = emptyMap()
    private var merges: List<Pair<String, String>> = emptyList()
    private var mergeRank: Map<Pair<String, String>, Int> = emptyMap()

    fun load(tokenizerJsonFile: File) {
        val json = JSONObject(tokenizerJsonFile.readText())
        val model = json.getJSONObject("model")

        // Load vocab
        val vocabObj = model.getJSONObject("vocab")
        val v = HashMap<String, Int>(vocabObj.length())
        val i2t = HashMap<Int, String>(vocabObj.length())
        for (key in vocabObj.keys()) {
            val id = vocabObj.getInt(key)
            v[key] = id
            i2t[id] = key
        }
        vocab = v
        idToToken = i2t

        // Load merges — each merge is either a 2-element array or a space-separated string
        val mergesArr = model.getJSONArray("merges")
        val m = ArrayList<Pair<String, String>>(mergesArr.length())
        val mr = HashMap<Pair<String, String>, Int>(mergesArr.length())
        for (i in 0 until mergesArr.length()) {
            val item = mergesArr.get(i)
            val pair = when (item) {
                is org.json.JSONArray -> {
                    if (item.length() >= 2) Pair(item.getString(0), item.getString(1)) else null
                }
                is String -> {
                    val parts = item.split(" ", limit = 2)
                    if (parts.size == 2) Pair(parts[0], parts[1]) else null
                }
                else -> null
            }
            if (pair != null) {
                m.add(pair)
                mr[pair] = i
            }
        }
        merges = m
        mergeRank = mr

        Timber.d("BpeTokenizer: loaded ${vocab.size} tokens, ${merges.size} merges")
    }

    val vocabSize: Int get() = vocab.size

    /**
     * Encode text into token IDs using BPE.
     */
    fun encode(text: String): List<Int> {
        if (vocab.isEmpty()) return emptyList()

        // Pre-tokenize: split on whitespace boundaries, prepend ▁ to each word
        val words = preTokenize(text)
        val result = mutableListOf<Int>()

        for (word in words) {
            val tokens = bpeEncode(word)
            for (token in tokens) {
                val id = vocab[token]
                if (id != null) {
                    result.add(id)
                } else {
                    // Unknown token — try byte fallback or skip
                    val unkId = vocab["<unk>"] ?: 3
                    result.add(unkId)
                }
            }
        }
        return result
    }

    /**
     * Decode token IDs back to text.
     */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            val token = idToToken[id] ?: continue
            if (token == "<s>" || token == "</s>" || token == "<pad>" || token == "<unk>") continue
            // Skip language tokens (they start with a letter and end with _Xxxx pattern)
            if (token.length > 4 && token.contains("_") && token[0].isLetter()) continue
            sb.append(token)
        }
        return sb.toString()
            .replace("\u2581", " ")
            .trimStart()
    }

    /**
     * Split text into words, prepending ▁ to each.
     * SentencePiece convention: spaces become ▁ prefix on following word.
     */
    private fun preTokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val words = text.split(" ").filter { it.isNotEmpty() }
        for ((i, word) in words.withIndex()) {
            // Always prepend ▁ (even for first word, SentencePiece convention)
            result.add("\u2581$word")
        }
        if (result.isEmpty() && text.isNotEmpty()) {
            result.add("\u2581$text")
        }
        return result
    }

    /**
     * Apply BPE merges to a single word.
     * Returns list of BPE tokens.
     */
    private fun bpeEncode(word: String): List<String> {
        if (word.isEmpty()) return emptyList()

        // Start with individual characters
        var symbols = word.map { it.toString() }.toMutableList()

        if (symbols.size <= 1) {
            // Single char — check if it's in vocab directly
            return if (vocab.containsKey(word)) listOf(word) else symbols
        }

        while (true) {
            if (symbols.size < 2) break

            // Find the pair with the lowest merge rank
            var bestPair: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE

            for (i in 0 until symbols.size - 1) {
                val pair = Pair(symbols[i], symbols[i + 1])
                val rank = mergeRank[pair]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestPair = pair
                }
            }

            if (bestPair == null) break // No more merges possible

            // Apply the merge: replace all occurrences of the pair
            val merged = bestPair.first + bestPair.second
            val newSymbols = mutableListOf<String>()
            var i = 0
            while (i < symbols.size) {
                if (i < symbols.size - 1 && symbols[i] == bestPair.first && symbols[i + 1] == bestPair.second) {
                    newSymbols.add(merged)
                    i += 2
                } else {
                    newSymbols.add(symbols[i])
                    i++
                }
            }
            symbols = newSymbols
        }

        return symbols
    }
}
