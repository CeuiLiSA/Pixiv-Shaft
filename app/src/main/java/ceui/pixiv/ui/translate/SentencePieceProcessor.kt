package ceui.pixiv.ui.translate

import org.json.JSONArray
import timber.log.Timber
import java.io.File

/**
 * Pure-Kotlin SentencePiece unigram tokenizer.
 *
 * Loads a vocabulary exported by prepare_translation_model.py (JSON array of {piece, score}).
 * Uses a Viterbi-like dynamic programming algorithm to find the optimal segmentation.
 */
class SentencePieceProcessor {

    private data class Piece(val text: String, val score: Float, val id: Int)

    private var pieces: List<Piece> = emptyList()
    private var pieceToId: Map<String, Int> = emptyMap()
    private var idToPiece: Map<Int, String> = emptyMap()

    // Trie for fast prefix matching
    private var trieRoot: TrieNode? = null

    private class TrieNode {
        val children = HashMap<Char, TrieNode>(4)
        var pieceIndex: Int = -1 // index in pieces list, -1 = not a piece
    }

    fun load(vocabFile: File) {
        val json = JSONArray(vocabFile.readText())
        val list = mutableListOf<Piece>()
        val p2i = HashMap<String, Int>(json.length())
        val i2p = HashMap<Int, String>(json.length())

        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val piece = Piece(
                text = obj.getString("piece"),
                score = obj.getDouble("score").toFloat(),
                id = i,
            )
            list.add(piece)
            p2i[piece.text] = i
            i2p[i] = piece.text
        }

        pieces = list
        pieceToId = p2i
        idToPiece = i2p

        // Build trie
        val root = TrieNode()
        for ((index, piece) in list.withIndex()) {
            var node = root
            for (ch in piece.text) {
                node = node.children.getOrPut(ch) { TrieNode() }
            }
            node.pieceIndex = index
        }
        trieRoot = root

        Timber.d("SentencePiece: loaded ${pieces.size} pieces from ${vocabFile.name}")
    }

    val vocabSize: Int get() = pieces.size

    /**
     * Encode text into token IDs using Viterbi segmentation.
     * SentencePiece convention: space is replaced with ▁ (U+2581) at word boundaries.
     */
    fun encode(text: String): List<Int> {
        if (pieces.isEmpty()) return emptyList()

        // SentencePiece normalizes by prepending ▁ and replacing spaces with ▁
        val normalized = "\u2581" + text.replace(" ", "\u2581")
        val n = normalized.length

        if (n == 0) return emptyList()

        // Viterbi forward pass
        // bestScore[i] = best log-prob score to segment normalized[0..i)
        val bestScore = FloatArray(n + 1) { if (it == 0) 0f else Float.NEGATIVE_INFINITY }
        val bestPieceLen = IntArray(n + 1) { 0 } // length of piece ending at position i

        val root = trieRoot ?: return emptyList()

        for (i in 0 until n) {
            if (bestScore[i] == Float.NEGATIVE_INFINITY) continue

            // Walk the trie from position i
            var node = root
            for (j in i until n) {
                val ch = normalized[j]
                node = node.children[ch] ?: break

                if (node.pieceIndex >= 0) {
                    val piece = pieces[node.pieceIndex]
                    val endPos = j + 1
                    val newScore = bestScore[i] + piece.score
                    if (newScore > bestScore[endPos]) {
                        bestScore[endPos] = newScore
                        bestPieceLen[endPos] = endPos - i
                    }
                }
            }

            // Fallback: if no piece matches single char, use unknown (byte fallback)
            if (bestScore[i + 1] == Float.NEGATIVE_INFINITY) {
                // Use a large penalty for unknown characters
                bestScore[i + 1] = bestScore[i] - 100f
                bestPieceLen[i + 1] = 1
            }
        }

        // Viterbi backward pass: reconstruct segmentation
        val tokens = mutableListOf<Int>()
        var pos = n
        while (pos > 0) {
            val len = bestPieceLen[pos]
            if (len <= 0) {
                // Should not happen, but safety fallback
                pos--
                continue
            }
            val substr = normalized.substring(pos - len, pos)
            val id = pieceToId[substr]
            if (id != null) {
                tokens.add(id)
            } else {
                // Unknown token: use <unk> (id=3 in most SPM models) or skip
                val unkId = pieceToId["<unk>"] ?: 3
                tokens.add(unkId)
            }
            pos -= len
        }

        tokens.reverse()
        return tokens
    }

    /**
     * Decode token IDs back to text.
     */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            val piece = idToPiece[id] ?: continue
            // Skip special tokens
            if (piece == "<s>" || piece == "</s>" || piece == "<pad>" || piece == "<unk>") continue
            sb.append(piece)
        }
        // SentencePiece convention: ▁ represents space, leading ▁ is removed
        return sb.toString()
            .replace("\u2581", " ")
            .trimStart()
    }
}
