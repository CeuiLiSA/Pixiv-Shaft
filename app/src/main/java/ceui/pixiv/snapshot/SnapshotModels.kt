package ceui.pixiv.snapshot

import ceui.loxia.Comment

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