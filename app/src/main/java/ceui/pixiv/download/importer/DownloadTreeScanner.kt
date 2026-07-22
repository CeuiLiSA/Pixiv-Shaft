package ceui.pixiv.download.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/** 扫到的一个图片文件。 */
data class ScannedFile(
    val docUri: Uri,
    val displayName: String,
    val lastModified: Long,
    val size: Long,
)

/**
 * 递归遍历用户授权的 SAF 目录树，吐出里面的图片文件。issue #953 的"扫描"环节。
 *
 * 隐私：只读用户在 SAF 里亲手选的那一棵树，不需要 `READ_MEDIA_IMAGES` /
 * `READ_EXTERNAL_STORAGE`（本项目 manifest 里这两个权限是显式移除的），
 * 更不会碰相册的其它部分。
 *
 * 性能：一层目录一次 cursor 查询（[DocumentsContract.buildChildDocumentsUriUsingTree]），
 * 和 [ceui.pixiv.ui.novel.local.LocalLibraryViewModel] 的列目录同源。
 * **绝对不要换成 `DocumentFile.listFiles()`** —— 那个是每个 child 一次 binder 往返，
 * 3 万文件的下载目录会直接卡死。
 */
object DownloadTreeScanner {

    /** 目录深度上限。Shaft 自己的模板最多 4 层，留足余量还能拦住"选了整张 SD 卡"。 */
    private const val MAX_DEPTH = 10

    /** 单次扫描的文件数上限 —— 预览要把结果全留在内存里，不能无上限。 */
    const val MAX_FILES = 100_000

    /** 目录数上限，防御被符号链接 / provider 实现绕出来的环。 */
    private const val MAX_DIRS = 50_000

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /**
     * 广度优先遍历 [treeUri]，每扫到一个图片文件就调一次 [onFile]。
     *
     * 挂起函数，必须在 IO 上跑。每处理一层目录检查一次协程取消 —— 用户点"取消"
     * 时最多再多读一层目录就停。
     *
     * @param onProgress 每读完一层目录回调一次 `(已扫文件数, 已进入目录数)`，给进度 UI 用。
     * @return 实际遍历到的图片文件总数（等于 [onFile] 的调用次数）。
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        onProgress: (files: Int, dirs: Int) -> Unit = { _, _ -> },
        onFile: (ScannedFile) -> Unit,
    ): Int {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: error("无法解析目录 uri：$treeUri")

        // kotlin.collections.ArrayDeque —— removeFirst() 返回非空 E，不像 java.util 那版
        // 是平台类型，解构时不用再 !!。
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(rootDocId to 0)

        var files = 0
        var dirs = 0
        val visited = HashSet<String>()
        visited.add(rootDocId)

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (docId, depth) = queue.removeFirst()
            dirs++
            if (dirs > MAX_DIRS) {
                Timber.tag(TAG).w("目录数超过 %d，停止扫描", MAX_DIRS)
                break
            }

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            // 单个目录读失败（权限被撤 / provider 抛）不该让整次扫描失败 —— 跳过继续。
            val cursor = try {
                context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "列目录失败，跳过 docId=%s", docId)
                null
            } ?: continue

            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val timeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val childId = c.getString(idIdx) ?: continue
                    val name = c.getString(nameIdx) ?: continue
                    val mime = c.getString(mimeIdx)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // visited 去重：provider 理论上不该给出环，但真出了环这里就是死循环。
                        if (depth < MAX_DEPTH && visited.add(childId)) {
                            queue.addLast(childId to depth + 1)
                        }
                        continue
                    }
                    if (!isImage(name, mime)) continue
                    if (files >= MAX_FILES) {
                        Timber.tag(TAG).w("文件数达到上限 %d，停止扫描", MAX_FILES)
                        queue.clear()
                        return@use
                    }
                    onFile(
                        ScannedFile(
                            docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                            displayName = name,
                            lastModified = if (c.isNull(timeIdx)) 0L else c.getLong(timeIdx),
                            size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx),
                        ),
                    )
                    files++
                }
            }
            onProgress(files, dirs)
        }
        return files
    }

    /**
     * mime 优先，扩展名兜底 —— 部分 provider 对不认识的后缀一律返
     * `application/octet-stream`，只看 mime 会漏掉真图片。
     */
    private fun isImage(name: String, mime: String?): Boolean {
        if (mime != null && mime.startsWith("image/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    private const val TAG = "DownloadTreeScanner"
}
