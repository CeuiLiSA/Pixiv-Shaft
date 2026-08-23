package ceui.pixiv.snapshot

import ceui.loxia.Comment
import java.io.File

const val SNAPSHOT_SCHEMA_VERSION = 1
const val SNAPSHOT_EXTENSION = ".shaftsnap"
const val SNAPSHOT_ROOT_DIR = "ShaftSnapshots"

const val SNAPSHOT_MANIFEST = "manifest.json"
const val SNAPSHOT_ILLUST_JSON = "illust.json"
const val SNAPSHOT_COMMENTS_JSON = "comments.json"
const val SNAPSHOT_ASSETS_JSON = "assets.json"

/** 单个离线快照的 manifest，v1 只含手动快照所需的字段。 */
data class SnapshotManifest(
    val schemaVersion: Int = SNAPSHOT_SCHEMA_VERSION,
    val snapshotId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val illustId: Long,
    val type: String = "illust",
    val includeComments: Boolean = false,
    val includeOriginal: Boolean = false,
    val isBookmarked: Boolean = false,
    val isFollowed: Boolean = false,
    val title: String? = null,
    val authorName: String? = null,
    val authorId: Long? = null,
    val coverPath: String? = null,
    val fileCount: Int = 0,
    val totalSize: Long = 0L,
)

/** assets.json：URL -> 快照目录内相对路径。渲染只允许读这些本地文件。 */
data class SnapshotAssets(
    val assets: Map<String, String> = emptyMap(),
)

/** comments.json：主评论 + 各自已拉取的回复。 */
data class SnapshotComments(
    val threads: List<SnapshotCommentThread> = emptyList(),
)

data class SnapshotCommentThread(
    val comment: Comment,
    val replies: List<Comment> = emptyList(),
)

class SnapshotException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 导出文件名统一生成：标题_作品ID.shaftsnap，并清理非法文件名字符。 */
fun SnapshotManifest.safeExportFileName(): String {
    val safeTitle = title
        ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        ?.takeIf { it.isNotBlank() }
        ?: "snapshot"
    return "${safeTitle}_${illustId}$SNAPSHOT_EXTENSION"
}

private val SNAPSHOT_ID_REGEX = Regex("[A-Za-z0-9_-]+")

/** 快照 ID 只允许 UUID 风格字符，防止外部 manifest 用 ../ 或空串做路径穿越。 */
fun requireSnapshotId(snapshotId: String): String {
    if (snapshotId.isBlank() || !SNAPSHOT_ID_REGEX.matches(snapshotId)) {
        throw SnapshotException("非法快照 ID: $snapshotId")
    }
    return snapshotId
}

/** 在 baseDir 内安全解析相对路径，防 ../ 或绝对路径逃逸。 */
fun safeResolve(baseDir: File, rel: String): File {
    val base = baseDir.normalize().absoluteFile
    val target = File(base, rel).normalize().absoluteFile
    val basePath = base.absolutePath
    val targetPath = target.absolutePath
    if (targetPath != basePath && !targetPath.startsWith(basePath + File.separator)) {
        throw SnapshotException("非法快照路径: $rel")
    }
    return target
}