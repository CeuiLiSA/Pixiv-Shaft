package ceui.pixiv.download.header

/**
 * User-configurable fields that can be emitted into the metadata header
 * prepended to downloaded novel TXT files.
 *
 * `SeriesTitle` / `SeriesIndex` are "series-only" — the renderer silently
 * skips them when the current novel is not part of a series. This keeps a
 * single preset reasonable for both standalone and series-chapter downloads
 * without forcing two preset sets (though users can still save separate
 * presets if they prefer).
 */
enum class HeaderField {
    Title,
    Author,
    AuthorId,
    NovelId,
    NovelLink,
    Caption,
    PublishTime,
    TextLength,
    Tags,
    SeriesTitle,
    SeriesIndex;

    companion object {
        /** Every field, in the default recommended order. */
        val ALL: List<HeaderField> = listOf(
            Title,
            Author,
            AuthorId,
            NovelId,
            NovelLink,
            SeriesTitle,
            SeriesIndex,
            PublishTime,
            TextLength,
            Tags,
            Caption,
        )

        /** Fields that only render when the novel has a series context. */
        val SERIES_ONLY: Set<HeaderField> = setOf(SeriesTitle, SeriesIndex)

        fun isSeriesOnly(f: HeaderField): Boolean = f in SERIES_ONLY
    }
}

/**
 * A named ordered list of fields. A field appears in the output only if it
 * is present in [fields] — deletion from the list == "unchecked". Ordering
 * in [fields] is the render order.
 */
data class HeaderPreset(
    val name: String,
    val fields: List<HeaderField>,
)

/**
 * All presets plus the currently active one. Persisted as a single JSON
 * blob via [HeaderConfigRepo].
 */
data class HeaderConfigStore(
    val presets: List<HeaderPreset>,
    val activeName: String,
) {
    fun activePreset(): HeaderPreset =
        presets.firstOrNull { it.name == activeName } ?: presets.first()
}
