package ceui.pixiv.download.template

/**
 * Conditions supported inside `[?...:...]` blocks. Closed set — extend by adding a
 * new variant, not by string matching.
 */
sealed interface Condition {
    data class Flag(val name: String, val negated: Boolean = false) : Condition
    data class PageGreaterThan(val threshold: Int) : Condition

    companion object {
        fun parse(raw: String): Condition {
            val trimmed = raw.trim()
            if (trimmed.startsWith("p>")) {
                val n = trimmed.removePrefix("p>").trim().toIntOrNull()
                    ?: error("Invalid condition: '$raw'")
                return PageGreaterThan(n)
            }
            val negated = trimmed.startsWith("!")
            val name = if (negated) trimmed.removePrefix("!").trim() else trimmed
            require(name.isNotEmpty()) { "Empty condition: '$raw'" }
            return Flag(name, negated)
        }
    }
}
