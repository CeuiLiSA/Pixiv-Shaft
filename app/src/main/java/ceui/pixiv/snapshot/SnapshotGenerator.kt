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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 从在线详情页生成一份离线快照。
 *
 * 数据源优先级：ObjectPool 已有完整元数据 -> 缺失时回 v1/illust/detail 补齐；
 * 图片优先复用 ImageLoaderV3 已下好的共享文件，缺失时由它联网拉取。
 *
 * 正式快照写 manifest.json；自动快照写 auto_manifest.json（内部格式，转正后才成为正式快照）。
 */
object SnapshotGenerator {

    /**
     * 已就绪页的本地复制宽度。这一段是纯本地 IO、不碰网络，可以宽一些；再往上只会让同一批
     * Glide 缓存文件的读与快照目录的写互相抢盘，收益迅速归零。
     */
    private const val COPY_PARALLELISM = 8

    /**
     * 需要联网取回的页的宽度。这里压的是**突发**而不是频率（串行同样是 N 个请求，只是摊开了），
     * 所以明显小于复制宽度即可，不必收到 1。
     */
    private const val DOWNLOAD_PARALLELISM = 3

    /** cache-only 探测的宽度：只是查表 + 命中时读缓存项，给足即可。 */
    private const val PROBE_PARALLELISM = 8

    /** 一页的落盘计划：序号、要取的 URL、目标相对路径。纯数据，构造它不产生任何 IO。 */
    private data class SnapshotPage(val index: Int, val url: String, val rel: String)

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
            // 先把每页的 URL 与目标路径算齐：这一步是纯函数、不碰 IO，于是「第 N 张图缺 URL」会在
            // 写下任何一个字节之前就抛出来（旧实现是边下边算，前几页已经落盘才发现最后一张没有 URL）。
            val pages = (0 until pageCount).map { i ->
                val url = bean.snapshotPageUrl(i, includeOriginal)
                    ?: throw SnapshotException(
                        appContext.getString(R.string.snapshot_error_page_url_missing, i + 1)
                    )
                SnapshotPage(index = i, url = url, rel = "images/p$i${url.snapshotExtension()}")
            }

            // 分拣：现在就能不联网拿到的（本地复制，可以并行）vs 要联网取回的（宽度压窄）。
            // 探测只是**乐观前置**：判「要联网」的页最终也可能命中 Glide 磁盘缓存、一个请求都不发。
            val localFiles = parallelMapOrdered(pages, PROBE_PARALLELISM) { ImageLoaderV3.peekCachedFile(it.url) }
            val readyPages = mutableListOf<Pair<SnapshotPage, File>>()
            val pendingPages = mutableListOf<SnapshotPage>()
            pages.forEachIndexed { i, plan ->
                val source = localFiles[i]
                if (source != null) readyPages += plan to source else pendingPages += plan
            }

            // 进度改成「已完成页数」：并行之后页序不再有意义，报页码反而会跳。
            val donePages = AtomicInteger()
            val copyGate = Semaphore(COPY_PARALLELISM)
            val downloadGate = Semaphore(DOWNLOAD_PARALLELISM)

            fun copyPage(plan: SnapshotPage, source: File) {
                copyFileTo(source, File(snapshotDir, plan.rel))
            }

            coroutineScope {
                // 未就绪页先起飞：联网这一段与下面的本地复制重叠；宽度由 downloadGate 压住，
                // 不会一次性把 N 页请求全打出去。
                val pendingJobs = pendingPages.map { plan ->
                    async {
                        val source = downloadGate.withPermit { fetchFile(appContext, plan.url) }
                        copyGate.withPermit { copyPage(plan, source) }
                        progress(R.string.snapshot_progress_images, donePages.incrementAndGet(), pageCount)
                    }
                }
                val readyJobs = readyPages.map { (plan, source) ->
                    async {
                        copyGate.withPermit { copyPage(plan, source) }
                        progress(R.string.snapshot_progress_images, donePages.incrementAndGet(), pageCount)
                    }
                }
                (readyJobs + pendingJobs).awaitAll()
            }

            // 全部落盘之后再单线程建映射：这一页只存一份文件，但渲染侧按哪一档分辨率来问由它自己
            // 决定，所以把该页所有尺寸变体都指到这份存档（多对一，不多下一个字节）。按页码顺序建，
            // 生成的 assets.json 逐字节可复现。
            pages.forEach { plan ->
                bean.snapshotPageVariantUrls(plan.index).forEach { variant -> assets[variant] = plan.rel }
                assets[plan.url] = plan.rel
                pagePaths += plan.rel
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
        copyFileTo(fetchFile(context, url), target)
    }

    /**
     * 取回一个 url 的可用文件。
     *
     * 失败统一包成 [SnapshotException]；取消照旧往上抛 —— 页面销毁导致的取消不是「下载失败」，
     * 包成异常会让上层把一次取消当错误处理。
     */
    private suspend fun fetchFile(context: Context, url: String): File = try {
        ImageLoaderV3.obtain(url).awaitFile()
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        throw SnapshotException(context.getString(R.string.snapshot_error_image_download, url), e)
    }

    /** 把已就绪的文件复制进快照目录。纯本地 IO，不联网。 */
    private fun copyFileTo(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun <T> writeJson(dir: File, name: String, value: T) {
        File(dir, name).writeText(Shaft.sGson.toJson(value))
    }
}