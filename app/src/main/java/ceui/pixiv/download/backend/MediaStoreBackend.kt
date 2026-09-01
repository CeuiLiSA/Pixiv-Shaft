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

    /**
     * On Q+ "exists" means a [MediaStore] row exists *and* its backing on-disk
     * file is still there. A row alone is not enough — when the user removes
     * files via a third-party file manager / system "Files" app / `adb shell rm`,
     * the bytes are gone but the row lingers because `ContentResolver.delete`
     * was never called. Treating that orphan row as "the file exists" used to
     * cause two distinct bad behaviours, both reported by users:
     *
     *   1. **Rename policy** ([Downloads.nextFreePath]) saw `exists() == true`
     *      and started cycling through ` (1).jpg`, ` (2).jpg` ... even though
     *      the original name was free on disk. A 100p illust re-downloaded
     *      after the user wiped its folder came back as `XXX (1).jpg`.
     *   2. **Skip policy** silently dropped legitimate downloads — the user
     *      asked for "skip if exists", deleted the files, expected re-download
     *      to refill the gap, and instead saw nothing happen.
     *
     * Detection strategy is conservative — drop the row only when we're
     * confident the file is gone:
     *   - Fast path: [legacyFile] reports the file present → keep the row,
     *     no probe needed (covers ~all healthy cases).
     *   - Slow path: legacy [File.exists] says no → some scoped-storage OEMs
     *     refuse to read public-storage paths via [File], so cross-check via
     *     [ContentResolver.openFileDescriptor]. `FileNotFoundException` is the
     *     unambiguous "row points to nothing" signal — only then do we delete
     *     the row. Any other failure (SecurityException from a foreign-owned
     *     row, IOException, …) is treated as "unknown — keep the row" so we
     *     never erase someone else's data on a flaky probe.
     *
     * [replace] has its own orphan reclaim path ([reclaimOrphanRow]) that
     * **needs** the row to stay around long enough to update in place. That
     * path doesn't go through [exists] (see [Downloads.applyOverwritePolicy]'s
     * Replace branch), so the cleanup here is safe for Replace too.
     */
    override fun exists(relPath: RelativePath): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = findUri(relPath) ?: return false
            if (legacyFile(relPath).exists()) return true
            return when (probeFileBehindRow(uri)) {
                ProbeResult.Present -> true
                ProbeResult.Missing -> {
                    // Row points to a file that no longer exists on disk.
                    // Drop it so Skip / Rename treat this slot as truly free —
                    // otherwise users see " (1)" suffixes after wiping the folder.
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    false
                }
                ProbeResult.Unknown -> true
            }
        }
        return legacyFile(relPath).exists()
    }

    private enum class ProbeResult { Present, Missing, Unknown }

    /**
     * Probe whether the on-disk file behind [uri] is still readable. Used to
     * disambiguate a stale row from a legitimately existing file when
     * [legacyFile] cannot see it (scoped-storage edge cases on certain OEMs).
     */
    private fun probeFileBehindRow(uri: Uri): ProbeResult = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { ProbeResult.Present }
            ?: ProbeResult.Unknown
    } catch (_: java.io.FileNotFoundException) {
        ProbeResult.Missing
    } catch (_: Exception) {
        ProbeResult.Unknown
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
                    return openExistingForReplace(existing, relPath, mime)
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
    private fun openExistingForReplace(
        existing: Uri,
        relPath: RelativePath,
        mime: String,
    ): StorageBackend.WriteHandle {
        val silent = SilentDownload.applies(mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        context.contentResolver.update(existing, values, null, null)
        // 变成 pending 的一刻起就要登记(理由见 openModern),所有退出路径 untrack。
        InFlightMediaStoreWrites.track(existing)
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
            InFlightMediaStoreWrites.untrack(existing)
            throw e
        }
        val onFinish: () -> Unit = {
            // 时序同 openModern:先回拨 pending 文件 mtime,再发布,再兜底更新时间列。
            if (silent) {
                SilentDownload.backdatePendingFile(context.contentResolver, existing)
            }
            val update = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(existing, update, null, null)
            if (silent) {
                SilentDownload.backdateRow(context.contentResolver, existing, legacyFile(relPath))
            }
            InFlightMediaStoreWrites.untrack(existing)
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
            InFlightMediaStoreWrites.untrack(existing)
        }
        return StorageBackend.WriteHandle(existing, stream, onFinish, onAbort)
    }

    private fun openModern(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
        // Facade-enforced invariant: the path is guaranteed free by the time
        // we get here. Always insert fresh so the row carries the correct mime.
        val collectionUri = collectionUri(mime)
        val relativeDir = (listOf(collectionRoot()) + relPath.directory).joinToString("/") + "/"
        val silent = SilentDownload.applies(mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, relPath.filename)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            // Hide the row from gallery apps until the bytes are flushed —
            // otherwise gallery apps may cache a 0-byte thumbnail and never
            // refresh, which is what users see as "doesn't appear in gallery".
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (silent) {
                // 低调下载:insert 时就带上回拨时间。DATE_ADDED 只在插入时赋值、
                // 后续扫描不重算,这里是它最可靠的落点;onFinish 里的 backdateRow
                // 作为 DATE_MODIFIED / DATE_TAKEN 的第二道保险。
                val backdateSec = SilentDownload.backdateSeconds()
                put(MediaStore.MediaColumns.DATE_ADDED, backdateSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, backdateSec)
            }
        }
        var target: Uri = insertPendingRow(collectionUri, values, silent, relPath)
        // 登记在途写入:低调下载把 DATE_ADDED 回拨后,MediaStoreOrphanCleaner 的
        // 60 秒时间闸认不出这是刚插入的行,必须显式登记防止被当孤儿清掉。
        // 下面所有提前退出路径(rename guard / openOutputStream 失败)都要 untrack。
        InFlightMediaStoreWrites.track(target)
        // OEM-rename guard: certain Android skins (HarmonyOS / MIUI / vivo etc.)
        // silently rewrite the inserted row's RELATIVE_PATH when an existing
        // on-disk file collides with the one we're trying to write but its
        // MediaStore row isn't visible to us. The result the user sees is
        // sibling directories `Pictures/ShaftImages (1)/`, `(2)/`, ...
        // each with a single file inside — instead of a normal collision
        // resolution on the filename within the requested directory.
        // We can't talk the OS out of doing this, but we can refuse to
        // commit bytes to the wrong place: verify the actual
        // RELATIVE_PATH on the row we just got, and if it was altered,
        // first try to self-heal (below), then delete the row and surface
        // an actionable error to the caller.
        var actualRelativeDir = queryRelativePath(target)
        if (actualRelativeDir != null && !relativePathsEqual(actualRelativeDir, relativeDir)) {
            Timber.w(
                "MediaStoreBackend: OEM auto-renamed insert from '%s' to '%s' for %s. " +
                    "On-disk file at the requested path likely owned by another package " +
                    "or hidden from MediaStore on this OEM. Trying reclaim-and-retry.",
                relativeDir, actualRelativeDir, relPath,
            )
            runCatching { context.contentResolver.delete(target, null, null) }
            InFlightMediaStoreWrites.untrack(target)
            // 自愈重试(仅一次):目录被改名的典型诱因是请求路径上有一个 MediaStore
            // 看不见的同名盘上文件(换机迁移/文件管理器拷入,issue #958)。强制扫描
            // 把它收编成可见 row 后重新 insert,MediaProvider 就会走正常的「文件名
            // 加 (1) 后缀」冲突解决,而不是把整个目录改名 —— bytes 落在用户配置的
            // 目录里,代价只是文件名多个后缀。
            var healed = false
            if (legacyFile(relPath).exists() && reclaimOrphanRow(relPath) != null) {
                target = insertPendingRow(collectionUri, values, silent, relPath)
                InFlightMediaStoreWrites.track(target)
                actualRelativeDir = queryRelativePath(target)
                healed = actualRelativeDir == null ||
                    relativePathsEqual(actualRelativeDir, relativeDir)
                if (!healed) {
                    runCatching { context.contentResolver.delete(target, null, null) }
                    InFlightMediaStoreWrites.untrack(target)
                }
            }
            if (!healed) {
                // For human readability fall back to the collection root
                // (Pictures/Downloads) when the relative path has no directory
                // segments — joining an empty list yields "" and produces the
                // confusing "无法写入 /" message.
                val displayDir = if (relPath.directory.isNotEmpty()) {
                    relPath.directory.joinToString("/")
                } else {
                    collectionRoot()
                }
                // 提示必须可执行:问题出在整个目录被系统改写(归属异常),不是某个
                // 同名旧文件 —— 旧文案叫用户「删同名旧文件」在这种场景下无从下手。
                error(
                    "下载目录被系统改写（$displayDir/ → $actualRelativeDir），无法写入。" +
                        "请在文件管理器把该文件夹整体重命名或移走，或在下载设置中更换下载路径后重试",
                )
            }
        }
        // If openOutputStream throws after the row was inserted, the row
        // would otherwise be left stranded as a `.pending-NNNN` 0-byte file.
        // Delete it before propagating so we don't leak orphans (issue #857).
        val stream = try {
            context.contentResolver.openOutputStream(target, "rwt")
                ?: error("openOutputStream returned null for $target")
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(target, null, null) }
            InFlightMediaStoreWrites.untrack(target)
            throw e
        }
        val onFinish: () -> Unit = {
            if (silent) {
                // 低调下载:必须在清 IS_PENDING 之前回拨 pending 文件的 mtime,
                // 发布扫描才会记下旧的 date_modified(见 backdatePendingFile 注释)。
                SilentDownload.backdatePendingFile(context.contentResolver, target)
            }
            // Clear IS_PENDING — this both makes the row visible to other apps
            // and fires a content observer notification that gallery apps use
            // to refresh their grid.
            val update = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(target, update, null, null)
            if (silent) {
                SilentDownload.backdateRow(context.contentResolver, target, legacyFile(relPath))
            }
            InFlightMediaStoreWrites.untrack(target)
        }
        // On abort, delete the row we just inserted. The bytes are partial /
        // zero, the row is invisible to galleries (still IS_PENDING=1), and
        // the user's file manager shows it as `.pending-NNNN`. Clean exit.
        val onAbort: () -> Unit = {
            runCatching { context.contentResolver.delete(target, null, null) }
            InFlightMediaStoreWrites.untrack(target)
        }
        return StorageBackend.WriteHandle(target, stream, onFinish, onAbort)
    }

    /**
     * Insert the IS_PENDING=1 row for [openModern]. 带日期列的 insert 在个别
     * OEM 上可能被拒 —— 低调下载时去掉日期列重试一次(低调下载开关绝不能让
     * 下载本身失败;少一道保险,onFinish 兜底仍在)。
     */
    private fun insertPendingRow(
        collectionUri: Uri,
        values: ContentValues,
        silent: Boolean,
        relPath: RelativePath,
    ): Uri {
        val inserted: Uri? = try {
            context.contentResolver.insert(collectionUri, values)
        } catch (e: Exception) {
            if (!silent) throw e
            Timber.w(e, "MediaStoreBackend: insert with backdated date columns rejected, retrying without")
            null
        }
        return inserted ?: run {
            if (silent) {
                values.remove(MediaStore.MediaColumns.DATE_ADDED)
                values.remove(MediaStore.MediaColumns.DATE_MODIFIED)
                context.contentResolver.insert(collectionUri, values)
            } else {
                null
            }
        } ?: error("MediaStore insert failed for $relPath")
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
            if (SilentDownload.applies(mime)) {
                // 低调下载:先回拨 mtime 再扫,scanner 照 mtime 记 date_modified;
                // 但 DATE_ADDED 由 scanner 记为「现在」,pre-Q 没有行所有权限制,
                // 扫完在回调里直接把时间列改掉,把另一半也回拨上。
                val stampMs = SilentDownload.backdateMillis()
                SilentDownload.backdateFile(file, stampMs)
                MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf(mime),
                ) { _, scannedUri ->
                    if (scannedUri != null) {
                        SilentDownload.backdateRow(context.contentResolver, scannedUri, null, stampMs)
                    }
                }
            } else {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            }
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
     * slash and letter case. The platform docs spell the value as
     * `Pictures/MyAlbum` (no trailing slash) but most apps — including
     * this one — pass values *with* a trailing slash, and AOSP / OEM
     * forks differ on whether they normalise it on write. Case matters
     * too: emulated external storage is case-insensitive, so when the
     * on-disk directory is a case variant of the requested one,
     * MediaProvider merges the insert into it and reads back the
     * *existing* directory's casing — same physical directory, not a
     * rename. A case-sensitive check here made [openModern]'s rename
     * guard hard-fail every single download in that state (issue #958).
     */
    private fun relativePathsEqual(a: String, b: String): Boolean =
        a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)

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

    /**
     * 插入用的集合 URI。
     *
     * 每个集合都有两道 provider 级的硬约束,插错了直接 `IllegalArgumentException`:
     *  - 收哪些 mime:`Images` 不收 `video/mp4`;
     *  - 允许哪些一级目录:`Images` = DCIM / Pictures,`Video` = DCIM / Movies / **Pictures**,
     *    而 `Files`/`Downloads` 只允许 Download/Documents(真机实测报的就是
     *    「Primary directory Pictures not allowed for content://media/external/file」)。
     *
     * 所以动图存成 mp4 时走 `Video` 集合 —— 它是唯一既收视频、又允许落在用户选的
     * `Pictures/...` 目录里的集合,文件因此仍和 GIF 成品待在同一个文件夹。
     *
     * 查询(mime 传 null)一律走 Files:它是全类型超集,图片行和视频行都看得到 ——
     * 「目标是否已存在」必须覆盖两种格式,否则用户切格式后 skip/rename 策略会失灵。
     */
    private fun collectionUri(mime: String? = null): Uri = when (collection) {
        // Downloads 集合本来就收任意 mime,不用换。
        StorageChoice.MediaStore.Collection.Downloads -> {
            @Suppress("NewApi")
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        StorageChoice.MediaStore.Collection.Images -> when {
            mime == null -> filesCollectionUri()
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun filesCollectionUri(): Uri = MediaStore.Files.getContentUri("external")

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
