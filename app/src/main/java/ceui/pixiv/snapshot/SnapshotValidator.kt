package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustsBean
import java.io.File

/**
 * 导入/打开前校验快照完整性：
 * - assets.json 里的每个映射都必须指向真实存在的文件；
 * - 渲染快照详情页会引用的 URL（作品页、作者头像、评论头像/表情）必须在 assets 中。
 */
object SnapshotValidator {

    fun validate(snapshotDir: File, manifest: SnapshotManifest) {
        requireSnapshotId(manifest.snapshotId)
        if (!File(snapshotDir, SNAPSHOT_MANIFEST).isFile) {
            throw SnapshotException("缺少 manifest.json")
        }
        val illust = readJson<IllustsBean>(File(snapshotDir, SNAPSHOT_ILLUST_JSON))
            ?: throw SnapshotException("缺少或无法解析 illust.json")
        val assets = readJson<SnapshotAssets>(File(snapshotDir, SNAPSHOT_ASSETS_JSON))
            ?: SnapshotAssets()

        manifest.coverPath?.let { rel -> safeResolve(snapshotDir, rel) }
        val missingAssets = assets.assets.filterValues { rel ->
            runCatching { safeResolve(snapshotDir, rel) }.getOrNull()?.isFile != true
        }
        if (missingAssets.isNotEmpty()) {
            val sample = missingAssets.entries.take(5).joinToString { "${it.key} -> ${it.value}" }
            throw SnapshotException("快照不完整：assets 指向不存在的文件或非法路径。$sample")
        }

        // 页图按「该页任一尺寸变体能落到真实文件」判定，与渲染侧 SnapshotViewerData.pageFile
        // 同一口径 —— 只认当初存的那一个 URL 的话，换个分辨率来问就会假阳性通过、真打开却空白。
        fun relIsFile(rel: String?): Boolean =
            rel != null && runCatching { safeResolve(snapshotDir, rel) }.getOrNull()?.isFile == true

        val pageCount = illust.page_count.coerceAtLeast(1)
        for (i in 0 until pageCount) {
            val ok = illust.snapshotPageVariantUrls(i).any { relIsFile(assets.assets[it]) } ||
                (i == 0 && relIsFile(manifest.coverPath))
            if (!ok) {
                throw SnapshotException("快照不完整：第 ${i + 1} 张图缺失")
            }
        }

        val required = linkedSetOf<String>()
        illust.snapshotAuthorAvatarUrl()?.let { required += it }

        if (manifest.includeComments) {
            val comments = readJson<SnapshotComments>(File(snapshotDir, SNAPSHOT_COMMENTS_JSON))
                ?: throw SnapshotException("快照声明包含评论，但缺少 comments.json")
            comments.threads.forEach { thread ->
                thread.comment.snapshotAvatarUrl()?.let { required += it }
                thread.comment.snapshotStampUrl()?.let { required += it }
                thread.replies.forEach { reply ->
                    reply.snapshotAvatarUrl()?.let { required += it }
                    reply.snapshotStampUrl()?.let { required += it }
                }
            }
        }

        val unresolved = required.filter { url ->
            val rel = assets.assets[url] ?: return@filter true
            runCatching { safeResolve(snapshotDir, rel) }.getOrNull()?.isFile != true
        }
        if (unresolved.isNotEmpty()) {
            val sample = unresolved.take(5).joinToString("\n")
            throw SnapshotException("快照不完整：以下渲染所需资源缺失：\n$sample")
        }
    }

    internal inline fun <reified T> readJson(file: File): T? = runCatching {
        if (!file.isFile) null else Shaft.sGson.fromJson(file.readText(), T::class.java)
    }.getOrNull()
}