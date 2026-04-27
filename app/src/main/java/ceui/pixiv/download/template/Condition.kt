package ceui.pixiv.download.template

/**
 * Conditions supported inside `[?...:...]` blocks. Closed set — extend by adding a
 * new variant, not by string matching.
 */
sealed interface Condition {
    data class Flag(val name: String, val negated: Boolean = false) : Condition
    data class PageGreaterThan(val threshold: Int, val negated: Boolean = false) : Condition

    companion object {
        fun parse(raw: String): Condition {
            val trimmed = raw.trim()
            val negated = trimmed.startsWith("!")
            val body = if (negated) trimmed.removePrefix("!").trim() else trimmed
            if (body.startsWith("p>")) {
                val n = body.removePrefix("p>").trim().toIntOrNull()
                    ?: error("Invalid condition: '$raw'")
                return PageGreaterThan(n, negated)
            }
            val name = body
            require(name.isNotEmpty()) { "Empty condition: '$raw'" }
            return Flag(name, negated)
        }
    }
}
