package ceui.pixiv.download.backend

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import ceui.lisa.BuildConfig.BUILD_TYPE
import ceui.pixiv.download.model.RelativePath
import timber.log.Timber
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Strategy: write under a user-chosen tree URI ([treeUri]) using [DocumentFile].
 * Creates (or reuses) each intermediate directory implied by [RelativePath.directory],
 * then creates (or overwrites) the final file.
 */
class SafBackend(
    private val context: Context,
    private val treeUri: Uri,
) : StorageBackend {
    // 只保留最轻量的缓存：路径 -> DocumentFile
    private val directoryCache = ConcurrentHashMap<String, DocumentFile>()

    // 并发控制：只在创建期间存在
    private val creatingPaths = ConcurrentHashMap<String, CreatingState>()
    private val pathLocks = ConcurrentHashMap<String, Any>()
    private data class CreatingState(
        val future: CompletableFuture<DocumentFile>,  // 注意：直接存 DocumentFile，不是 PathNode
        val thread: Thread,
        val startTime: Long
    )
    // Parent doc cache — segments are stable within a download burst, so after
    // the first hit we skip the per-save findFile chain entirely.
    // @Volatile private var dirCache: Pair<List<String>, DocumentFile>? = null

    // Skip exists+delete (each = O(N) findFile listFiles IPC). SAF createFile
    // auto-suffixes "x (1).ext" on collision, so duplicate saves produce a
    // sibling instead of truly overwriting — required to keep save time flat
    // when the target directory has tens of thousands of files (issue: review
    // report "downloads slow past 30k files").
    override fun replace(relPath: RelativePath, mime: String): StorageBackend.WriteHandle =
        open(relPath, mime)

    override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        // Facade-enforced invariant: the filename slot is guaranteed free by
        // the time we get here. We create unconditionally so the new document
        // carries the correct mime.
        val parent = ensureDirectory(relPath.directory)
        val doc = parent.createFile(mime, relPath.filename)
            ?: error("DocumentFile.createFile returned null for $relPath under $treeUri")
        // If openOutputStream throws, delete the empty document before
        // propagating so we don't leak 0-byte SAF files (issue #857).
        val stream = try {
            context.contentResolver.openOutputStream(doc.uri, "rwt")
                ?: error("openOutputStream returned null for ${doc.uri}")
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
        val onFinish: () -> Unit = {
            // Best-effort gallery visibility: if the SAF tree maps to a real
            // path on shared storage, hand it to MediaScanner so the new file
            // shows up in the gallery without waiting for the next system rescan.
            // For trees on non-primary volumes (SD card, USB) we may not be able
            // to derive a path — in that case we skip silently.
            resolveFsPath(doc.uri)?.let { fsPath ->
                if (SilentDownload.applies(mime)) {
                    // 低调下载:不主动通知 MediaScanner。扫描产生的行不归本 app 所有,
                    // Q+ 上事后回拨时间列会被权限拒绝,mtime 也常被 scoped storage 挡下。
                    // 干脆不广而告之 —— 文件立刻对相册 / 选图器完全不可见,等将来系统
                    // 全盘重扫时才以(尽力回拨过的)mtime 入库,不会出现在「最近」前排。
                    SilentDownload.backdateFile(File(fsPath))
                } else {
                    MediaScannerConnection.scanFile(context, arrayOf(fsPath), arrayOf(mime), null)
                }
            }
        }
        // On abort, delete the SAF document we just created (always
        // unconditional — createFile produced a fresh document we own).
        val onAbort: () -> Unit = {
            runCatching { doc.delete() }
        }
        return StorageBackend.WriteHandle(doc.uri, stream, onFinish, onAbort)
    }

    private fun resolveFsPath(docUri: Uri): String? = runCatching {
        // Only ExternalStorageProvider exposes "volume:relative" doc IDs we can
        // reliably translate to a filesystem path. Downloads/MediaStore/Drive
        // providers use opaque IDs — for those, defer to the next system rescan.
        if (docUri.authority != "com.android.externalstorage.documents") return@runCatching null
        val docId = DocumentsContract.getDocumentId(docUri)
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return@runCatching null
        val (volume, relative) = parts
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        // Skip exists() — on scoped storage we may not have read access to
        // arbitrary public paths even though the file was just written via SAF.
        // MediaScannerConnection silently no-ops on missing paths anyway.
        File(root, relative).absolutePath
    }.getOrNull()

    override fun exists(relPath: RelativePath): Boolean {
        val parent = findDirectory(relPath.directory) ?: return false
        return parent.findFile(relPath.filename)?.exists() == true
    }

    override fun delete(relPath: RelativePath): Boolean {
        val parent = findDirectory(relPath.directory) ?: return false
        val doc = parent.findFile(relPath.filename) ?: return false
        return doc.delete()
    }
    /**
     * @wangwang-code: 为createDirectory添加原子性检查，解决并发场景下多个线程同时请求同一目录路径时的竞争问题。
     * 同时为了避免频繁的锁操作，引入了新的缓存机制。考虑到内存占用问题，我们只缓存最近创建的几个目录。
     * **/
    private fun ensureDirectory(segments: List<String>): DocumentFile {
        val fullPath = segments.joinToString("/")

        // 1. 快速路径：缓存命中
        directoryCache[fullPath]?.let {
            recordStats(fullPath, "hit", -1)
            return it
        }

        // 2. 检查是否正在创建中（带超时保护）
        val state = creatingPaths[fullPath]
        if (state != null) {
            recordStats(fullPath, "waiting", -1)
            try {
                // 添加超时避免永久阻塞
                val result = state.future.get(30, java.util.concurrent.TimeUnit.SECONDS)
                recordStats(fullPath, "wait_success", -1)
                return result
            } catch (e: java.util.concurrent.TimeoutException) {
                recordStats(fullPath, "wait_timeout", -1)
                // 超时后尝试清理并重试
                creatingPaths.remove(fullPath, state)
                // 继续执行，让当前线程获得创建权
            } catch (e: Exception) {
                recordStats(fullPath, "wait_failed", -1)
                creatingPaths.remove(fullPath, state)
                // 失败后重试
            }
        }

        // 3. 获取创建权
        val lock = getLockForPath(fullPath)
        synchronized(lock) {
            // 双重检查
            directoryCache[fullPath]?.let {
                recordStats(fullPath, "hit_after_lock", -1)
                return it
            }

            // 再次检查创建状态
            val existingState = creatingPaths[fullPath]
            if (existingState != null) {
                try {
                    return existingState.future.get(30, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    creatingPaths.remove(fullPath, existingState)
                    // 超时后继续执行创建
                } catch (e: Exception) {
                    creatingPaths.remove(fullPath, existingState)
                    throw e
                }
            }

            // 检查磁盘是否已存在
            val existingDir = findDirectory(segments)
            if (existingDir != null) {
                // 发现已存在，放入缓存
                directoryCache[fullPath] = existingDir
                recordStats(fullPath, "exists_on_disk_fast", -1)
                return existingDir
            }

            // 当前线程获得创建权
            recordStats(fullPath, "creating", -1)
            val createFuture = CompletableFuture<DocumentFile>()
            val creatingState = CreatingState(
                future = createFuture,
                thread = Thread.currentThread(),
                startTime = System.currentTimeMillis()
            )
            creatingPaths[fullPath] = creatingState

            try {
                // 执行创建
                val startTime = System.currentTimeMillis()
                var cur = root()
                var currentPath = ""
                // 记录本次创建过程中缓存的中间路径，用于失败回滚
                val cachedIntermediatePaths = mutableListOf<String>()

                for (seg in segments) {
                    currentPath = if (currentPath.isEmpty()) seg else "$currentPath/$seg"

                    // 检查下级路径是否已缓存
                    val cached = directoryCache[currentPath]
                    if (cached != null) {
                        cur = cached
                    } else {
                        val existing = cur.findFile(seg)
                        cur = when {
                            existing == null -> {
                                cur.createDirectory(seg)
                                    ?: error("Could not create directory '$seg' under ${cur.uri}")
                            }
                            existing.isDirectory -> existing
                            else -> error("Path segment '$seg' exists but is not a directory")
                        }
                        // 缓存每一级路径，并记录以便回滚
                        directoryCache[currentPath] = cur
                        cachedIntermediatePaths.add(currentPath)
                    }
                }

                // 最终路径缓存
                directoryCache[fullPath] = cur

                if (BUILD_TYPE.contains("debug")) {
                    // 记录创建耗时
                    val duration = System.currentTimeMillis() - startTime
                    recordStats(fullPath, "created", duration)
                    if (duration > 100) {
                        Timber.tag("DirectoryCache").w("Slow creation: '$fullPath' took ${duration}ms")
                    }
                }

                // 创建完成，通知所有等待线程
                createFuture.complete(cur)
                // 清理创建状态
                creatingPaths.remove(fullPath, creatingState)

                return cur

            } catch (e: Exception) {
                // 创建失败，通知所有等待线程
                createFuture.completeExceptionally(e)
                // 清理创建状态（使用 remove(key, value) 确保只清理当前线程的状态）
                creatingPaths.remove(fullPath, creatingState)
                // 清除可能的部分缓存（只移除本次创建的路径）
                directoryCache.remove(fullPath)
                // 注意：中间路径缓存保留，因为可能被其他成功创建的线程使用
                // 如果中间路径创建失败，它们应该已经被回滚
                throw e
            }
        }
    }

    private fun getLockForPath(path: String): Any {
        // 使用 computeIfAbsent 确保线程安全
        return pathLocks.computeIfAbsent(path) { Any() }
    }

    private fun recordStats(path: String, event: String, duration: Long) {
        // 输出日志或收集统计
        if (BUILD_TYPE.contains("debug")) {
            Timber.tag("DirectoryCache").d("[$event] '$path' took ${duration}ms")
        }
    }

    private fun findDirectory(segments: List<String>): DocumentFile? {
        var cur: DocumentFile? = root()
        for (seg in segments) {
            cur = cur?.findFile(seg)?.takeIf { it.isDirectory } ?: return null
        }
        return cur
    }

    private fun root(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: error("DocumentFile.fromTreeUri failed for $treeUri — tree not accessible")
}
