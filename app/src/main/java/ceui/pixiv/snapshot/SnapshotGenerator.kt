package ceui.pixiv.snapshot

import android.content.Context
import androidx.annotation.StringRes
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Comment
import ceui.pixiv.utils.fetchFullIllustDetail
import ceui.pixiv.utils.hasTrustedCaption
import ceui.pixiv.utils.isFullDetail
import ceui.pixiv.imageloader.ImageLoaderV3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 从在线详情页生成一份离线快照。
 *
 * 数据源优先级：ObjectPool 已有完整元数据 -> 缺失时回 v1/illust/detail 补齐；
 * 图片优先复用 ImageLoaderV3 已下好的共享文件，缺失时由它联网拉取。
 *
 * 正式快照写 manifest.json；自动快照写 auto_manifest.json（内部格式，转正后才成为正式快照）。
 */
object SnapshotGenerator {

    suspend fun generate(
        context: Context,
        illust: Illust,
        includeComments: Boolean,
        includeOriginal: Boolean,
        onProgress: suspend (String) -> Unit = {},
    ): SnapshotManifest = withContext(Dispatchers.IO) {
        val content = generateContent(
            context = context,
            illust = illust,
            includeComments = includeComments,
            includeOriginal = includeOriginal,
            onProgress = onProgress,
        )
        try {
            val manifest = content.toSnapshotManifest(includeComments, includeOriginal)
            writeJson(content.snapshotDir, SNAPSHOT_MANIFEST, manifest)
            manifest
        } catch (e: Exception) {
            content.snapshotDir.deleteRecursively()
            throw e
        }
    }

    suspend fun generateAuto(
        context: Context,
        illust: Illust,
        includeOriginal: Boolean = false,
        onProgress: suspend (String) -> Unit = {},
    ): AutoSnapshotManifest = withContext(Dispatchers.IO) {
        val content = generateContent(
            context = context,
            illust = illust,
            includeComments = false,
            includeOriginal = includeOriginal,
            onProgress = onProgress,
        )
        try {
            val manifest = content.toAutoSnapshotManifest(includeOriginal)
            writeJson(content.snapshotDir, AUTO_SNAPSHOT_MANIFEST, manifest)
            manifest
        } catch (e: Exception) {
            content.snapshotDir.deleteRecursively()
            throw e
        }
    }

    private data class SnapshotContent(
        val snapshotDir: File,
        val snapshotId: String,
        val bean: Illust,
        val pageCount: Int,
        val assets: Map<String, String>,
        val pagePaths: List<String>,
        val comments: SnapshotComments?,
        val fileCount: Int,
        val totalSize: Long,
        val createdAt: Long,
    )

    private suspend fun generateContent(
        context: Context,
        illust: Illust,
        includeComments: Boolean,
        includeOriginal: Boolean,
        onProgress: suspend (String) -> Unit,
    ): SnapshotContent = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (illust.isGif()) {
            throw SnapshotException(appContext.getString(R.string.snapshot_unsupported_ugoira))
        }
        val snapshotId = UUID.randomUUID().toString()
        val snapshotDir = SnapshotRepository.createSnapshotDir(appContext, snapshotId)
        // 进度文案是给用户看的,统一走资源;领域层不硬编码任何一种语言的 UI 串。
        suspend fun progress(@StringRes resId: Int, vararg args: Any) =
            onProgress(appContext.getString(resId, *args))

        try {
            progress(R.string.snapshot_progress_metadata)
            val bean = if (illust.isFullDetail() && illust.hasTrustedCaption()) {
                illust
            } else {
                // toUpdate = false：快照生成只是读取完整元数据，不应改动 ObjectPool。
                fetchFullIllustDetail(illust.id, false) ?: illust
            }

            val assets = linkedMapOf<String, String>()
            val pagePaths = mutableListOf<String>()

            val pageCount = bean.page_count.coerceAtLeast(1)
            for (i in 0 until pageCount) {
                progress(R.string.snapshot_progress_images, i + 1, pageCount)
                val url = bean.snapshotPageUrl(i, includeOriginal)
                    ?: throw SnapshotException(
                        appContext.getString(R.string.snapshot_error_page_url_missing, i + 1)
                    )
                val rel = "images/p$i${url.snapshotExtension()}"
                copyUrlTo(appContext, url, File(snapshotDir, rel))
                // 这一页只存一份文件，但渲染侧按哪一档分辨率来问由它自己决定：
                // 把该页所有尺寸变体都指到这份存档，assets 是多对一映射，不多下一个字节。
                bean.snapshotPageVariantUrls(i).forEach { variant -> assets[variant] = rel }
                assets[url] = rel
                pagePaths += rel
            }

            // 多图作品的封面(illust 级 image_urls)不属于任何一页，指到 p0。
            if (pageCount > 1) {
                pagePaths.firstOrNull()?.let { coverRel ->
                    bean.snapshotCoverVariantUrls().forEach { variant -> assets[variant] = coverRel }
                }
            }

            bean.snapshotAuthorAvatarUrl()?.let { url ->
                progress(R.string.snapshot_progress_avatar)
                val rel = "avatars/author${url.snapshotExtension()}"
                copyUrlTo(appContext, url, File(snapshotDir, rel))
                assets[url] = rel
            }

            val commentData = if (includeComments) {
                progress(R.string.snapshot_progress_comments)
                val response = Client.appApi.getIllustComments(bean.id)
                val threads = response.comments.map { comment ->
                    val replies = if (comment.has_replies) {
                        runCatching { Client.appApi.getIllustReplyComments("illust", comment.id).comments }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    SnapshotCommentThread(comment, replies)
                }
                progress(R.string.snapshot_progress_comment_assets)
                val avatarRelByUrl = mutableMapOf<String, String>()
                val stampRelByUrl = mutableMapOf<String, String>()
                threads.forEach { thread ->
                    downloadCommentAssets(appContext, thread.comment, snapshotDir, assets, avatarRelByUrl, stampRelByUrl)
                    thread.replies.forEach { downloadCommentAssets(appContext, it, snapshotDir, assets, avatarRelByUrl, stampRelByUrl) }
                }
                SnapshotComments(threads)
            } else {
                null
            }

            progress(R.string.snapshot_progress_writing)
            writeJson(snapshotDir, SNAPSHOT_ILLUST_JSON, bean)
            if (commentData != null) {
                writeJson(snapshotDir, SNAPSHOT_COMMENTS_JSON, commentData)
            }
            writeJson(snapshotDir, SNAPSHOT_ASSETS_JSON, SnapshotAssets(assets))

            val fileCount = snapshotDir.walkTopDown().count { it.isFile }
            val totalSize = snapshotDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            SnapshotContent(
                snapshotDir = snapshotDir,
                snapshotId = snapshotId,
                bean = bean,
                pageCount = pageCount,
                assets = assets,
                pagePaths = pagePaths,
                comments = commentData,
                fileCount = fileCount,
                totalSize = totalSize,
                createdAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            snapshotDir.deleteRecursively()
            throw e
        }
    }

    private fun SnapshotContent.toSnapshotManifest(
        includeComments: Boolean,
        includeOriginal: Boolean,
    ): SnapshotManifest = SnapshotManifest(
        snapshotId = snapshotId,
        createdAt = createdAt,
        illustId = bean.id,
        type = bean.type ?: "illust",
        includeComments = includeComments,
        includeOriginal = includeOriginal,
        isBookmarked = bean.isBookmarked,
        isFollowed = bean.user?.is_followed ?: false,
        xRestrict = bean.x_restrict,
        pageCount = pageCount,
        title = bean.title,
        authorName = bean.user?.name,
        authorId = bean.user?.id,
        coverPath = pagePaths.firstOrNull(),
        fileCount = fileCount,
        totalSize = totalSize,
    )

    private fun SnapshotContent.toAutoSnapshotManifest(
        includeOriginal: Boolean,
    ): AutoSnapshotManifest = AutoSnapshotManifest(
        snapshotId = snapshotId,
        createdAt = createdAt,
        illustId = bean.id,
        type = bean.type ?: "illust",
        includeComments = false,
        includeOriginal = includeOriginal,
        isBookmarked = bean.isBookmarked,
        isFollowed = bean.user?.is_followed ?: false,
        xRestrict = bean.x_restrict,
        pageCount = pageCount,
        title = bean.title,
        authorName = bean.user?.name,
        authorId = bean.user?.id,
        coverPath = pagePaths.firstOrNull(),
        fileCount = fileCount,
        totalSize = totalSize,
    )

    private suspend fun downloadCommentAssets(
        context: Context,
        comment: Comment,
        snapshotDir: File,
        assets: MutableMap<String, String>,
        avatarRelByUrl: MutableMap<String, String>,
        stampRelByUrl: MutableMap<String, String>,
    ) {
        comment.snapshotAvatarUrl()?.let { url ->
            val rel = avatarRelByUrl.getOrPut(url) {
                val newRel = "avatars/comment_${comment.id}${url.snapshotExtension()}"
                copyUrlTo(context, url, File(snapshotDir, newRel))
                newRel
            }
            assets[url] = rel
        }
        comment.snapshotStampUrl()?.let { url ->
            val rel = stampRelByUrl.getOrPut(url) {
                val newRel = "stamps/${comment.id}${url.snapshotExtension()}"
                copyUrlTo(context, url, File(snapshotDir, newRel))
                newRel
            }
            assets[url] = rel
        }
    }

    private suspend fun copyUrlTo(context: Context, url: String, target: File) {
        target.parentFile?.mkdirs()
        val source = try {
            ImageLoaderV3.obtain(url).awaitFile()
        } catch (e: Exception) {
            throw SnapshotException(context.getString(R.string.snapshot_error_image_download, url), e)
        }
        source.copyTo(target, overwrite = true)
    }

    private fun <T> writeJson(dir: File, name: String, value: T) {
        File(dir, name).writeText(Shaft.sGson.toJson(value))
    }
}