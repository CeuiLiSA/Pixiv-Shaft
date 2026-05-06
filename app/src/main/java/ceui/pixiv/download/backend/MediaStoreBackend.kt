package ceui.pixiv.download.backend

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.RelativePath
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Strategy: write via [MediaStore] on Android 10+, and via the legacy public
 * external-storage [File] API on older devices.
 *
 * [RelativePath] is interpreted as `directory/.../filename` relative to the
 * collection's root (Pictures or Downloads).
 */
class MediaStoreBackend(
    private val context: Context,
    private val collection: StorageChoice.MediaStore.Collection,
) : StorageBackend {

    override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openModern(relPath, mime)
        } else {
            openLegacy(relPath, mime)
        }
    }

    override fun exists(relPath: RelativePath): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return findUri(relPath) != null
        }
        return legacyFile(relPath).exists()
    }

    override fun delete(relPath: RelativePath): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findUri(relPath) ?: return false
            return context.contentResolver.delete(uri, null, null) > 0
        }
        return legacyFile(relPath).delete()
    }

    /**
     * On Q+, update the existing MediaStore row in place instead of
     * delete + insert. This avoids `contentResolver.delete()` which
     * triggers media-deletion alerts on HarmonyOS and similar skins.
     *
     * Two distinct failure modes are handled here:
     *
     *  1. **Orphan rows from external scanners.** A file may exist on disk
     *     but its row's `RELATIVE_PATH` was normalised differently by a
     *     system migration / 3rd-party scanner so [findUri] misses. The
     *     `findUri ?: reclaimOrphanRow` chain forces MediaScanner to ingest
     *     it before we fall through, otherwise [openModern] would trigger
     *     OEM-side directory auto-rename
     *     (`Pictures/ShaftImages (1)/`, `(2)/`, ...).
     *
     *  2. **Row exists but not owned by us.** Typical after app reinstall
     *     or when another package created the row first. On Q+
     *     MediaProvider rejects our `update`/`delete` with
     *     `SecurityException`, breaking the download chain. We catch it
     *     and fall through to insert; MediaStore auto-suffixes DISPLAY_NAME
     *     on conflict so bytes land on a sibling " (1)" file. Net better
     *     than failing the download outright — price is a duplicate file.
     */
    override fun replace(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // First the canonical + _DATA lookup. If both miss but a file
            // physically exists at the requested on-disk path, force MediaStore
            // to ingest it via [reclaimOrphanRow] — otherwise we'd fall through
            // to insert and trigger the OEM-side directory auto-rename
            // (`Pictures/ShaftImages (1)/`, `(2)/`, ... scattering, see
            // [openModern]'s rename guard for the matching diagnostic).
            val existing = findUri(relPath) ?: reclaimOrphanRow(relPath)
            if (existing != null) {
                try {
                    return openExistingForReplace(existing, mime)
                } catch (se: SecurityException) {
                    // Row exists but we don't own it; cannot in-place update.
                    // Log + fall through to fresh insert below. MediaStore on
                    // Q+ auto-resolves DISPLAY_NAME conflicts within the same
                    // RELATIVE_PATH by appending a counter, so the bytes still
                    // land on disk just under a "(1)" filename.
                    android.util.Log.w(
                        "MediaStoreBackend",
                        "replace() denied on $existing — row not owned by us " +
                            "(reinstall or external scanner). Falling back to insert.",
                        se,
                    )
                }
            }
            // Reach here under two distinct conditions:
            //   1. All three lookups (canonical / _DATA / scanFile reclaim) missed —
            //      no row to update; this is a clean fresh write.
            //   2. A row was found but [openExistingForReplace] hit SecurityException
            //      because we don't own it (reinstall / external scanner). Fall back
            //      to insert; MediaStore Q+ auto-suffixes DISPLAY_NAME on conflict,
            //      so bytes land on a sibling "(1)" file.
            // In both cases skip [super.replace], whose default impl would call
            // `exists()` again (one more redundant findUri round-trip), and go
            // straight to insert. The inserted row carries our OEM-rename guard,
            // so this is the safe entry to a fresh write.
            return openModern(relPath, mime)
        }
        return super.replace(relPath, mime)
    }

    /**
     * In-place update path of [replace] — extracted so we can wrap it in a
     * single try/catch SecurityException and cleanly fall back to insert.
     */
    private fun openExistingForReplace(existing: Uri, mime: String): StorageBackend.WriteHandle {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        context.contentResolver.update(existing, values, null, null)
        // If openOutputStream throws, restore IS_PENDING=0 on the
        // existing row before propagating — otherwise the pre-existing
        // file gets stuck as a `.pending-` orphan even though we never
        // wrote a byte (issue #857 manifested via "replace" path).
        val stream = try {
            context.contentResolver.openOutputStream(existing, "rwt")
                ?: error("openOutputStream returned null for $existing")
        } catch (e: Exception) {
            runCatching {
                context.contentResolver.update(
                    existing,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null,
                )
            }
            throw e
        }
        val onFinish: () -> Unit = {
            val update = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(existing, update, null, null)
        }
        // On abort during replace, restore IS_PENDING=0 — the row
        // pre-existed before we touched it, so deleting it would
        // unilaterally erase a file the user already had.
        val onAbort: () -> Unit = {
            runCatching {
                context.contentResolver.update(
                    existing,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null,
                )
            }
        }
        return StorageBackend.WriteHandle(existing, stream, onFinish, onAbort)
    }

    private fun openModern(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        // Facade-enforced invariant: the path is guaranteed free by the time
        // we get here. Always insert fresh so the row carries the correct mime.
        val collectionUri = collectionUri()
        val relativeDir = (listOf(collectionRoot()) + relPath.directory).joinToString("/") + "/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, relPath.filename)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            // Hide the row from gallery apps until the bytes are flushed —
            // otherwise gallery apps may cache a 0-byte thumbnail and never
            // refresh, which is what users see as "doesn't appear in gallery".
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target: Uri = context.contentResolver.insert(collectionUri, values)
            ?: error("MediaStore insert failed for $relPath")
        // OEM-rename guard: certain Android skins (HarmonyOS / MIUI / etc.)
        // silently rewrite the inserted row's RELATIVE_PATH when an existing
        // on-disk file collides with the one we're trying to write but its
        // MediaStore row isn't visible to us. The result the user sees is
        // sibling directories `Pictures/ShaftImages (1)/`, `(2)/`, ...
        // each with a single file inside — instead of a normal collision
        // resolution on the filename within the requested directory.
        // We can't talk the OS out of doing this, but we can refuse to
        // commit bytes to the wrong place: verify the actual
        // RELATIVE_PATH on the row we just got, and if it was altered,
        // delete the row and surface a clear error to the caller.
        val actualRelativeDir = queryRelativePath(target)
        if (actualRelativeDir != null && !relativePathsEqual(actualRelativeDir, relativeDir)) {
            // Detail goes to the log so we can correlate user reports with the
            // specific file/path; the user-visible exception keeps a short,
            // actionable message because it propagates straight to a toast.
            Timber.w(
                "MediaStoreBackend: OEM auto-renamed insert from '%s' to '%s' for %s. " +
                    "On-disk file at the requested path likely owned by another package " +
                    "or hidden from MediaStore on this OEM (HarmonyOS / MIUI / etc.). " +
                    "Aborting write to avoid scattering files into the wrong directory.",
                relativeDir, actualRelativeDir, relPath,
            )
            runCatching { context.contentResolver.delete(target, null, null) }
            // For human readability fall back to the collection root
            // (Pictures/Downloads) when the relative path has no directory
            // segments — joining an empty list yields "" and produces the
            // confusing "无法写入 /" message.
            val displayDir = if (relPath.directory.isNotEmpty()) {
                relPath.directory.joinToString("/")
            } else {
                collectionRoot()
            }
            error("下载目录被系统占用，无法写入 $displayDir/，请在文件管理器删掉同名旧文件后重试")
        }
        // If openOutputStream throws after the row was inserted, the row
        // would otherwise be left stranded as a `.pending-NNNN` 0-byte file.
        // Delete it before propagating so we don't leak orphans (issue #857).
        val stream = try {
            context.contentResolver.openOutputStream(target, "rwt")
                ?: error("openOutputStream returned null for $target")
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(target, null, null) }
            throw e
        }
        val onFinish: () -> Unit = {
            // Clear IS_PENDING — this both makes the row visible to other apps
            // and fires a content observer notification that gallery apps use
            // to refresh their grid.
            val update = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(target, update, null, null)
        }
        // On abort, delete the row we just inserted. The bytes are partial /
        // zero, the row is invisible to galleries (still IS_PENDING=1), and
        // the user's file manager shows it as `.pending-NNNN`. Clean exit.
        val onAbort: () -> Unit = {
            runCatching { context.contentResolver.delete(target, null, null) }
        }
        return StorageBackend.WriteHandle(target, stream, onFinish, onAbort)
    }

    private fun openLegacy(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        val file = legacyFile(relPath)
        file.parentFile?.mkdirs()
        val newlyCreated = !file.exists() && file.createNewFile()
        // FileOutputStream 失败极罕见（disk full / 同时撤权限），但一旦失败
        // 调用方拿不到 WriteHandle、抓不到 onAbort，刚 createNewFile 的 0 字节
        // 文件就泄漏。和 openModern / SafBackend 保持一致：失败前先把刚创建的
        // 文件删掉再抛。
        val stream: OutputStream = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            if (newlyCreated) runCatching { file.delete() }
            throw e
        }
        val onFinish: () -> Unit = {
            // Pre-Q public-storage write — file is real, just tell MediaScanner.
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        }
        // On abort, only delete if we created the file ourselves — never
        // delete a pre-existing file the user already had on disk.
        val onAbort: () -> Unit = {
            if (newlyCreated) runCatching { file.delete() }
        }
        return StorageBackend.WriteHandle(Uri.fromFile(file), stream, onFinish, onAbort)
    }

    private fun findUri(relPath: RelativePath): Uri? {
        val relativeDir = (listOf(collectionRoot()) + relPath.directory).joinToString("/") + "/"
        // Canonical lookup: the row we just inserted (or one written by an
        // earlier session of this app) is keyed by (DISPLAY_NAME,
        // RELATIVE_PATH). Hits in the steady state.
        queryUri(collectionUri(), relPath.filename, relativeDir)?.let { return it }
        // Fallback: locate orphan rows whose RELATIVE_PATH doesn't match
        // our canonical form. Seen in practice when:
        //   - app was reinstalled and the existing row's owner became "no
        //     one" but the row's RELATIVE_PATH was normalised differently
        //     by a system migration (no trailing slash, etc.);
        //   - the file was put on disk by another tool that scanned with
        //     a slightly different RELATIVE_PATH.
        // Without this fallback, [replace] mistakenly believes the file
        // doesn't exist, falls through to insert-fresh, and on certain
        // OEM skins MediaStore reacts to the on-disk collision by
        // auto-renaming the *directory* — scattering files into
        // `ShaftImages (1)/`, `ShaftImages (2)/`, ... silently.
        return queryUriByData(collectionUri(), legacyFile(relPath).absolutePath)
    }

    private fun queryUri(collectionUri: Uri, displayName: String, relativeDir: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(displayName, relativeDir)
        context.contentResolver.query(collectionUri, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collectionUri, id.toString())
            }
        }
        return null
    }

    /**
     * Compare two MediaStore `RELATIVE_PATH` strings ignoring trailing
     * slash. The platform docs spell the value as `Pictures/MyAlbum` (no
     * trailing slash) but most apps — including this one — pass values
     * *with* a trailing slash, and AOSP / OEM forks differ on whether
     * they normalise it on write. A bare equality check here would
     * therefore raise false positives in [openModern]'s rename guard
     * every time the OS chose the canonical-without-slash form on
     * read-back.
     */
    private fun relativePathsEqual(a: String, b: String): Boolean =
        a.trimEnd('/') == b.trimEnd('/')

    /**
     * Read back the [MediaStore.MediaColumns.RELATIVE_PATH] the OS actually
     * stored on [uri]. Used by [openModern] to detect OEM-side directory
     * auto-rename. Returns `null` if the row was deleted or the column is
     * not exposed (extremely unlikely on Q+).
     */
    private fun queryRelativePath(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
    }

    /**
     * Force MediaStore to ingest an on-disk file that no row currently
     * tracks (or whose row is invisible to us). Returns the freshly-
     * resolved [Uri] on success, `null` if the file does not exist on
     * disk or scanning fails / times out.
     *
     * This converts the "row missing, file present" state — which would
     * otherwise fall through to insert and let HarmonyOS / MIUI decide
     * to silently allocate `Pictures/ShaftImages (N)/` — into a normal
     * "row found" state, after which [replace] can update in place and
     * the bytes land in the user's configured directory.
     *
     * Implementation note: [android.provider.MediaStore.scanFile] is
     * `@SystemApi` (system-only); the only public scan trigger is
     * [MediaScannerConnection.scanFile] which is callback-based, so we
     * gate completion through a [CountDownLatch] with a short timeout.
     * Caller MUST be on a worker thread (the [StorageBackend] facade
     * already enforces this).
     */
    private fun reclaimOrphanRow(relPath: RelativePath): Uri? {
        val file = legacyFile(relPath)
        if (!file.exists()) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference<Uri?>(null)
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
            ) { _, scannedUri ->
                resultRef.set(scannedUri)
                latch.countDown()
            }
        } catch (t: Throwable) {
            Timber.w(t, "MediaStoreBackend: scanFile schedule failed for ${file.absolutePath}")
            return null
        }
        val completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            Timber.w("MediaStoreBackend: scanFile timed out for ${file.absolutePath}")
            return null
        }
        val scanned = resultRef.get() ?: return null
        // Prefer the canonical (DISPLAY_NAME, RELATIVE_PATH) Uri on our
        // collection — some Android skins gate ContentResolver.update by
        // collection authority, and scanFile may hand back a Files-view
        // Uri that update() then silently no-ops on.
        return findUri(relPath) ?: scanned
    }

    @Suppress("DEPRECATION")
    private fun queryUriByData(collectionUri: Uri, absolutePath: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DATA}=?"
        val args = arrayOf(absolutePath)
        context.contentResolver.query(collectionUri, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collectionUri, id.toString())
            }
        }
        return null
    }

    private fun collectionUri(): Uri = when (collection) {
        StorageChoice.MediaStore.Collection.Images    -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        StorageChoice.MediaStore.Collection.Downloads -> {
            @Suppress("NewApi")
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
    }

    private fun collectionRoot(): String = when (collection) {
        StorageChoice.MediaStore.Collection.Images    -> Environment.DIRECTORY_PICTURES
        StorageChoice.MediaStore.Collection.Downloads -> Environment.DIRECTORY_DOWNLOADS
    }

    private fun legacyFile(relPath: RelativePath): File {
        val root = Environment.getExternalStoragePublicDirectory(collectionRoot())
        val dir = relPath.directory.fold(root) { acc, seg -> File(acc, seg) }
        return File(dir, relPath.filename)
    }
}
