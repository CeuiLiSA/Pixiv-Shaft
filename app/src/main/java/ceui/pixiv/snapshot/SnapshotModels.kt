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
const val AUTO_SNAPSHOT_MANIFEST = "auto_manifest.json"
const val AUTO_SNAPSHOT_SCHEMA_VERSION = 1

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
    val xRestrict: Int? = null,
    val pageCount: Int? = null,
    val title: String? = null,
    val authorName: String? = null,
    val authorId: Long? = null,
    val coverPath: String? = null,
    val fileCount: Int = 0,
    val totalSize: Long = 0L,
)

/** 自动快照的 manifest，v1 只收录自动生成所需的展示/转正字段。 */
data class AutoSnapshotManifest(
    val schemaVersion: Int = AUTO_SNAPSHOT_SCHEMA_VERSION,
    val snapshotId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val illustId: Long,
    val type: String = "illust",
    val includeComments: Boolean = false,
    val includeOriginal: Boolean = false,
    val isBookmarked: Boolean = false,
    val isFollowed: Boolean = false,
    val xRestrict: Int? = null,
    val pageCount: Int? = null,
    val title: String? = null,
    val authorName: String? = null,
    val authorId: Long? = null,
    val coverPath: String? = null,
    val fileCount: Int = 0,
    val totalSize: Long = 0L,
)

/** 管理页展示自动快照时使用的轻量摘要。 */
data class AutoSnapshotSummary(
    val manifest: AutoSnapshotManifest,
    val fileCount: Int,
    val totalSize: Long,
    val coverFile: File?,
)

/** 管理页列表统一模型：正式快照或自动快照。 */
sealed interface SnapshotCard {
    val snapshotId: String
    val isAuto: Boolean
    val createdAt: Long
    val illustId: Long
    val type: String
    val title: String?
    val authorName: String?
    val authorId: Long?
    val xRestrict: Int?
    val pageCount: Int?
    val includeComments: Boolean
    val includeOriginal: Boolean
    val coverFile: File?
    val fileCount: Int
    val totalSize: Long
}

data class FormalSnapshotCard(val summary: SnapshotSummary) : SnapshotCard {
    override val snapshotId get() = summary.manifest.snapshotId
    override val isAuto get() = false
    override val createdAt get() = summary.manifest.createdAt
    override val illustId get() = summary.manifest.illustId
    override val type get() = summary.manifest.type
    override val title get() = summary.manifest.title
    override val authorName get() = summary.manifest.authorName
    override val authorId get() = summary.manifest.authorId
    override val xRestrict get() = summary.manifest.xRestrict
    override val pageCount get() = summary.manifest.pageCount
    override val includeComments get() = summary.manifest.includeComments
    override val includeOriginal get() = summary.manifest.includeOriginal
    override val coverFile get() = summary.coverFile
    override val fileCount get() = summary.fileCount
    override val totalSize get() = summary.totalSize
}

data class AutoSnapshotCard(val summary: AutoSnapshotSummary) : SnapshotCard {
    override val snapshotId get() = summary.manifest.snapshotId
    override val isAuto get() = true
    override val createdAt get() = summary.manifest.createdAt
    override val illustId get() = summary.manifest.illustId
    override val type get() = summary.manifest.type
    override val title get() = summary.manifest.title
    override val authorName get() = summary.manifest.authorName
    override val authorId get() = summary.manifest.authorId
    override val xRestrict get() = summary.manifest.xRestrict
    override val pageCount get() = summary.manifest.pageCount
    override val includeComments get() = summary.manifest.includeComments
    override val includeOriginal get() = summary.manifest.includeOriginal
    override val coverFile get() = summary.coverFile
    override val fileCount get() = summary.fileCount
    override val totalSize get() = summary.totalSize
}

/** 自动快照在内存中转为正式 manifest 的轻量映射；不写盘、不保留自动来源痕迹。 */
fun AutoSnapshotManifest.toSnapshotManifest(): SnapshotManifest = SnapshotManifest(
    schemaVersion = SNAPSHOT_SCHEMA_VERSION,
    snapshotId = snapshotId,
    createdAt = createdAt,
    illustId = illustId,
    type = type,
    includeComments = includeComments,
    includeOriginal = includeOriginal,
    isBookmarked = isBookmarked,
    isFollowed = isFollowed,
    xRestrict = xRestrict,
    pageCount = pageCount,
    title = title,
    authorName = authorName,
    authorId = authorId,
    coverPath = coverPath,
    fileCount = fileCount,
    totalSize = totalSize,
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