package ceui.pixiv.download.sanitize

import ceui.pixiv.download.model.RelativePath

/**
 * The project's single sanitization rule for filesystem-bound path segments.
 *
 * Responsibilities:
 *   - replace illegal characters (control chars + Windows/Linux reserved set) with `_`
 *   - strip leading/trailing whitespace and dots from each segment
 *   - cap each segment at [MAX_SEGMENT_BYTES] UTF-8 bytes, preserving the extension
 *     on the last segment (the filename)
 *   - replace emptied-out segments with [FALLBACK_SEGMENT]
 *
 * Applied after template rendering; never in parallel with any ad-hoc cleaner.
 */
object FsSanitizer {

    const val MAX_SEGMENT_BYTES = 200
    const val FALLBACK_SEGMENT = "_"

    private val ILLEGAL = Regex("[\\p{Cntrl}\\\\/:*?\"<>|]")

    fun clean(path: RelativePath): RelativePath =
        path.mapSegments { idx, seg -> cleanSegment(seg, preserveExtension = idx == path.segments.lastIndex) }

    fun cleanSegment(raw: String, preserveExtension: Boolean = false): String {
        // Replace illegal chars, then strip any leading/trailing whitespace and
        // dots. Windows rejects trailing dots/spaces; on ext4 they're legal but
        // invisible and trivially confusable — cleaner to drop unconditionally.
        val scrubbed = ILLEGAL.replace(raw, "_").trim { it.isWhitespace() || it == '.' }
        if (scrubbed.isEmpty()) return FALLBACK_SEGMENT
        return capBytes(scrubbed, preserveExtension)
    }

    private fun capBytes(s: String, preserveExtension: Boolean): String {
        if (s.toByteArray(Charsets.UTF_8).size <= MAX_SEGMENT_BYTES) return s

        val dot = if (preserveExtension) s.lastIndexOf('.') else -1
        val ext = if (dot in 1 until s.length) s.substring(dot) else ""
        val stem = if (ext.isNotEmpty()) s.substring(0, dot) else s

        val extBytes = ext.toByteArray(Charsets.UTF_8).size
        val budget = (MAX_SEGMENT_BYTES - extBytes).coerceAtLeast(1)
        return truncateToBytes(stem, budget) + ext
    }

    private fun truncateToBytes(s: String, maxBytes: Int): String {
        val out = StringBuilder()
        var used = 0
        for (ch in s) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            out.append(ch); used += size
        }
        return out.toString().ifEmpty { FALLBACK_SEGMENT }
    }
}
