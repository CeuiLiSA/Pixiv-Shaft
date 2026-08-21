package ceui.pixiv.snapshot

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustsBean
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.fetchFullIllustDetail
import ceui.loxia.hasTrustedCaption
import ceui.loxia.isFullDetail
import ceui.pixiv.imageloader.ImageLoaderV3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 从在线详情页生成一份 v1 手动快照。
 *
 * 数据源优先级：ObjectPool 已有完整元数据 -> 缺失时回 v1/illust/detail 补齐；
 * 图片优先复用 ImageLoaderV3 已下好的共享文件，缺失时由它联网拉取。
 */
object SnapshotGenerator {

    suspend fun generate(
        context: Context,
        illust: IllustsBean,
        includeComments: Boolean,
        includeOriginal: Boolean,
        onProgress: (String) -> Unit = {},
    ): SnapshotManifest = withContext(Dispatchers.IO) {
        if (illust.isGif) {
            throw SnapshotException("动图 / ugoira 暂不支持离线快照")
        }
        val appContext = context.applicationContext
        val snapshotId = UUID.randomUUID().toString()
        val snapshotDir = SnapshotRepository.createSnapshotDir(appContext, snapshotId)

        try {
            onProgress("准备作品元数据")
            val bean = if (illust.isFullDetail() && illust.hasTrustedCaption()) {
                illust
            } else {
                fetchFullIllustDetail(illust.id.toLong()) ?: illust
            }

            val assets = linkedMapOf<String, String>()
            val pagePaths = mutableListOf<String>()

            val pageCount = bean.page_count.coerceAtLeast(1)
            for (i in 0 until pageCount) {
                onProgress("下载作品图片 ${i + 1}/$pageCount")
                val url = bean.snapshotPageUrl(i, includeOriginal)
                    ?: throw SnapshotException("作品第 ${i + 1} 张图缺少可用 URL")
                val rel = "images/p$i${url.snapshotExtension()}"
                copyUrlTo(appContext, url, File(snapshotDir, rel))
                assets[url] = rel
                pagePaths += rel
            }

            bean.snapshotAuthorAvatarUrl()?.let { url ->
                onProgress("下载作者头像")
                val rel = "avatars/author${url.snapshotExtension()}"
                copyUrlTo(appContext, url, File(snapshotDir, rel))
                assets[url] = rel
            }

            val commentData = if (includeComments) {
                onProgress("获取评论")
                val response = Client.appApi.getIllustComments(bean.id.toLong())
                val threads = response.comments.map { comment ->
                    val replies = if (comment.has_replies) {
                        runCatching { Client.appApi.getIllustReplyComments("illust", comment.id).comments }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    SnapshotCommentThread(comment, replies)
                }
                onProgress("下载评论头像/表情")
                threads.forEach { thread ->
                    downloadCommentAssets(appContext, thread.comment, snapshotDir, assets)
                    thread.replies.forEach { downloadCommentAssets(appContext, it, snapshotDir, assets) }
                }
                SnapshotComments(threads)
            } else {
                null
            }

            onProgress("写入快照")
            writeJson(snapshotDir, SNAPSHOT_ILLUST_JSON, bean)
            if (commentData != null) {
                writeJson(snapshotDir, SNAPSHOT_COMMENTS_JSON, commentData)
            }
            writeJson(snapshotDir, SNAPSHOT_ASSETS_JSON, SnapshotAssets(assets))

            val fileCount = snapshotDir.walkTopDown().count { it.isFile }
            val totalSize = snapshotDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val manifest = SnapshotManifest(
                snapshotId = snapshotId,
                createdAt = System.currentTimeMillis(),
                illustId = bean.id.toLong(),
                type = bean.type ?: "illust",
                includeComments = includeComments,
                includeOriginal = includeOriginal,
                title = bean.title,
                authorName = bean.user?.name,
                authorId = bean.user?.id?.toLong(),
                coverPath = pagePaths.firstOrNull(),
                fileCount = fileCount,
                totalSize = totalSize,
            )
            writeJson(snapshotDir, SNAPSHOT_MANIFEST, manifest)
            manifest
        } catch (e: Exception) {
            snapshotDir.deleteRecursively()
            throw e
        }
    }

    private suspend fun downloadCommentAssets(
        context: Context,
        comment: Comment,
        snapshotDir: File,
        assets: MutableMap<String, String>,
    ) {
        comment.snapshotAvatarUrl()?.let { url ->
            val rel = "avatars/comment_${comment.id}${url.snapshotExtension()}"
            copyUrlTo(context, url, File(snapshotDir, rel))
            assets[url] = rel
        }
        comment.snapshotStampUrl()?.let { url ->
            val rel = "stamps/${comment.id}${url.snapshotExtension()}"
            copyUrlTo(context, url, File(snapshotDir, rel))
            assets[url] = rel
        }
    }

    private suspend fun copyUrlTo(context: Context, url: String, target: File) {
        target.parentFile?.mkdirs()
        val source = try {
            ImageLoaderV3.obtain(url).awaitFile()
        } catch (e: Exception) {
            throw SnapshotException("图片下载失败: $url", e)
        }
        source.copyTo(target, overwrite = true)
    }

    private fun <T> writeJson(dir: File, name: String, value: T) {
        File(dir, name).writeText(Shaft.sGson.toJson(value))
    }
}