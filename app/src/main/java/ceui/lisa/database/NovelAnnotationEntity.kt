package ceui.lisa.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a user-created annotation on a novel: highlight, note, or both.
 *
 * - Pure highlight: [note] is empty, [kind] = KIND_HIGHLIGHT
 * - Pure note: [note] is non-empty, [kind] = KIND_NOTE (optional highlight still allowed)
 */
@Entity(
    tableName = "novel_annotation_table",
    indices = [
        Index(value = ["novelId"]),
        Index(value = ["novelId", "charStart"]),
    ],
)
data class NovelAnnotationEntity(
    @PrimaryKey(autoGenerate = true)
    val annotationId: Long = 0,
    val novelId: Long,
    val charStart: Int,
    val charEnd: Int,
    val excerpt: String,
    val note: String = "",
    val color: Int,
    val kind: Int = KIND_HIGHLIGHT,
    val createdTime: Long = System.currentTimeMillis(),
    val updatedTime: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_HIGHLIGHT = 0
        const val KIND_NOTE = 1
    }
}
