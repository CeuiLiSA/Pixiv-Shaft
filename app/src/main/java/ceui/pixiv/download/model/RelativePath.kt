package ceui.pixiv.download.model

@JvmInline
value class RelativePath(val segments: List<String>) {

    init {
        require(segments.isNotEmpty()) { "RelativePath must have at least one segment (the filename)" }
        require(segments.none { it.contains('/') || it.contains('\\') }) {
            "Individual segments must not contain path separators"
        }
    }

    val filename: String get() = segments.last()
    val directory: List<String> get() = segments.dropLast(1)

    fun joinTo(separator: String = "/"): String = segments.joinToString(separator)

    fun mapSegments(transform: (Int, String) -> String): RelativePath =
        RelativePath(segments.mapIndexed(transform))

    override fun toString(): String = joinTo()

    companion object {
        fun parse(raw: String): RelativePath {
            val parts = raw.split('/', '\\').filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "RelativePath cannot be empty: '$raw'" }
            return RelativePath(parts)
        }
    }
}
