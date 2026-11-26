package ceui.pixiv.ui.pinned

import java.text.Normalizer
import java.util.Locale

/**
 * 「我置顶的内容」多语言关键词匹配器（issue #1398）。
 *
 * 面向中 / 日 / 英混合内容做统一归一化：
 * - NFKC：折叠全半角字母/数字/标点（`Ｒ－１８` ↔ `R-18`、`ＡＢＣ` ↔ `ABC`、全角空格 `　`）与半角片假名；
 * - 小写化（`Locale.ROOT`）：英文大小写不敏感；
 * - 日文片假名 → 平假名折叠（`ァ..ヶ` → `ぁ..ゖ`、`ヽヾ` → `ゝゞ`），用户输入平假名或片假名均可命中；
 * - 多词按空白切分做 AND 匹配，每个词只要命中任意一个待检索字段（原文 / 译名 / 昵称 / 账号 / UID）即通过。
 */
internal object PinnedSearchMatcher {

    private val WHITESPACE = Regex("\\s+")

    fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            out.append(foldJapaneseKana(ch))
        }
        return out.toString()
    }

    fun tokenize(query: String?): List<String> {
        val normalized = normalize(query).trim()
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(WHITESPACE).filter { it.isNotEmpty() }
    }

    /** 预计算条目的归一化检索字段，供列表过滤热路径复用，避免每次按键重跑 NFKC。 */
    fun buildIndex(vararg fields: String?): List<String> =
        fields.mapNotNull { raw -> normalize(raw).takeIf { it.isNotEmpty() } }

    fun matchesIndex(tokens: List<String>, normalizedFields: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        if (normalizedFields.isEmpty()) return false
        return tokens.all { token ->
            normalizedFields.any { field -> field.contains(token) }
        }
    }

    fun matches(tokens: List<String>, vararg fields: String?): Boolean {
        if (tokens.isEmpty()) return true
        return matchesIndex(tokens, buildIndex(*fields))
    }

    private fun foldJapaneseKana(ch: Char): Char = when (ch) {
        in '\u30A1'..'\u30F6' -> (ch.code - 0x60).toChar()
        '\u30FD' -> '\u309D'
        '\u30FE' -> '\u309E'
        else -> ch
    }
}
