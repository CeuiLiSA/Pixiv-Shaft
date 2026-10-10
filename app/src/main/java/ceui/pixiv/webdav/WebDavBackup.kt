package ceui.pixiv.webdav

import android.content.Context
import android.os.Build
import ceui.lisa.utils.BackupUtils
import ceui.pixiv.db.HistoryBackfill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/**
 * 把本地数据备份到用户自己的 WebDAV，或从 WebDAV 还原（pixez-flutter#1290 同款需求）。
 *
 * 载荷就是设置页「备份」导出的 Shaft-Backup.json（[BackupUtils.writeBackup]：设置、屏蔽、
 * 搜索记录、置顶作者、V3 下载配置，可选浏览记录），gzip 压缩后上传；两边格式互通，从
 * WebDAV 下载的 `.json.gz` 也能直接走本地「还原」。
 *
 * 与本地导出的唯一区别：**不上传登录凭据**。pixiv 账号（含 refresh_token，账号可绑定支付）
 * 和 Settings 里的第三方密钥不离开本机——WebDAV 多是第三方网盘，备份文件在那边是明文。
 * 还原时这几项保留本机现值，元数据由 [BackupUtils.restoreBackupEntity] 统一处理。
 *
 * 每次备份是一个新文件（文件名带 UTC 时间与设备型号），不覆盖旧文件；多台设备可共用一个
 * 目录，按设备各保留最近 [KEEP_PER_DEVICE] 份。
 */
object WebDavBackup {

    private const val TAG = "WebDav"
    private const val FILE_PREFIX = "Shaft-Backup_"
    private const val FILE_SUFFIX = ".json.gz"
    private const val CONTENT_TYPE = "application/gzip"
    const val KEEP_PER_DEVICE = 10

    private val FILE_PATTERN = Regex("""^Shaft-Backup_(\d{8}T\d{6}(?:\d{3})?Z)_(.+?)(?:_([0-9a-f]{32}))?\.json\.gz$""")

    /** 备份与还原互斥：手动操作与后台自动备份都在本进程里，一把锁就够。 */
    private val mutex = Mutex()

    /** 远端的一份备份。 */
    data class RemoteBackup(
        val name: String,
        val size: Long,
        val timeMs: Long,
        val device: String,
        val deviceId: String? = null,
    )

    sealed interface BackupResult {
        data class Uploaded(val name: String, val size: Long) : BackupResult

        /** 自动备份：内容与上次上传的一致，且远端还在，本次不再上传。 */
        data object Unchanged : BackupResult
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        WebDavClient(config).checkAccess()
    }

    /**
     * 打包并上传一份备份。[skipIfUnchanged] 供自动备份用：用户没动过任何数据时不制造重复文件。
     * 上传成功后顺手清理本设备多出的旧备份（失败不影响结果）。
     */
    suspend fun backup(
        context: Context,
        config: WebDavConfig,
        includeHistory: Boolean,
        skipIfUnchanged: Boolean,
    ): BackupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val client = WebDavClient(config)
            val temp = File.createTempFile("webdav-backup", FILE_SUFFIX, context.cacheDir)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                // 摘要算在压缩前的 JSON 上：gzip 头里没有时间戳之类的变量，但不依赖它更稳。
                BackupUtils.writeBackup(
                    context.applicationContext,
                    includeHistory,
                    false,
                    DigestOutputStream(GZIPOutputStream(temp.outputStream().buffered()), digest),
                )
                val hex = digest.digest().joinToString("") { "%02x".format(it) }

                // 只认「上次上传的那个文件还在」：只看目录里有没有任意备份的话，本机的备份被用户在网盘
                // 里删掉（或被同型号设备的清理挤掉）后，目录里只剩别的设备的文件，内容不变时就永远不再上传。
                val last = WebDavPrefs.lastUpload
                if (skipIfUnchanged && last != null && last.digest == hex &&
                    client.listFiles().any { it.name == last.name }
                ) {
                    Timber.tag(TAG).i("[backup] content unchanged, skip upload")
                    return@withContext BackupResult.Unchanged
                }

                client.ensureFolder()
                val name = newFileName(System.currentTimeMillis(), deviceLabel(), WebDavPrefs.deviceId)
                val size = temp.length()
                client.upload(name, temp, CONTENT_TYPE)
                WebDavPrefs.lastUpload = WebDavLastUpload(hex, name)
                Timber.tag(TAG).i("[backup] uploaded %s (%d bytes, history=%b)", name, size, includeHistory)

                try {
                    prune(client)
                } catch (e: IOException) {
                    Timber.tag(TAG).w(e, "[backup] prune failed")
                }
                BackupResult.Uploaded(name, size)
            } finally {
                temp.delete()
            }
        }
    }

    /** 远端全部备份，最新的在前。 */
    suspend fun list(config: WebDavConfig): List<RemoteBackup> = withContext(Dispatchers.IO) {
        WebDavClient(config).listFiles()
            .mapNotNull(::parse)
            .sortedByDescending { it.timeMs }
    }

    /**
     * 下载并还原一份备份。与本地「还原」同一条链路（[BackupUtils.restoreBackupEntity]：
     * 各表按主键合并，Settings 整份替换），之后把设备本地状态写回去。
     *
     * @throws IOException 下载失败或文件不是有效的备份
     */
    suspend fun restore(context: Context, config: WebDavConfig, backup: RemoteBackup) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val temp = File.createTempFile("webdav-restore", FILE_SUFFIX, appContext.cacheDir)
            try {
                WebDavClient(config).download(backup.name, temp)
                val restored = temp.inputStream().use { BackupUtils.restoreBackupEntity(appContext, it) }
                    ?: throw IOException("not a valid Shaft backup: ${backup.name}")
                // 开了浏览记录云同步时，历史页读的是云端，刚灌进本地库的条目要推上去才看得见；
                // 回填是幂等的（已有条目 no-op），清标记重跑即可（同 BrowseHistoryBackup 导入）。
                HistoryBackfill.maybeSchedule()
                Timber.tag(TAG).i("[restore] applied %s (settings=%b)", backup.name, restored.settings != null)
            } finally {
                temp.delete()
            }
        }
    }

    /** 每台设备只留最近 [KEEP_PER_DEVICE] 份，别的设备的备份不受这台设备备份频率影响。 */
    private fun prune(client: WebDavClient) {
        val stale = staleBackups(client.listFiles(), WebDavPrefs.deviceId)
        stale.forEach {
            client.delete(it.name)
            Timber.tag(TAG).d("[prune] deleted %s", it.name)
        }
    }

    internal fun staleBackups(entries: List<WebDavEntry>, deviceId: String): List<RemoteBackup> =
        entries.mapNotNull(::parse)
            .filter { it.deviceId == deviceId }
            .sortedByDescending { it.timeMs }
            .drop(KEEP_PER_DEVICE)

    internal fun newFileName(timeMs: Long, device: String, deviceId: String): String =
        "$FILE_PREFIX${timestampFormat(true).format(Date(timeMs))}_${device}_$deviceId$FILE_SUFFIX"

    internal fun parse(entry: WebDavEntry): RemoteBackup? {
        val match = FILE_PATTERN.matchEntire(entry.name) ?: return null
        val timestamp = match.groupValues[1]
        val time = runCatching { timestampFormat(timestamp.length == 19).parse(timestamp)?.time }
            .getOrNull() ?: return null
        return RemoteBackup(entry.name, entry.size, time, match.groupValues[2], match.groupValues[3].ifEmpty { null })
    }

    /** UTC：不同时区的设备共用一个目录时，按文件名排序就是按时间排序。 */
    private fun timestampFormat(milliseconds: Boolean) = SimpleDateFormat(
        if (milliseconds) "yyyyMMdd'T'HHmmssSSS'Z'" else "yyyyMMdd'T'HHmmss'Z'", Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    /** 只留 URL 安全字符，避免各家服务器对 href 编码处理不一致。 */
    private fun deviceLabel(): String =
        Build.MODEL.orEmpty().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(32)
            .ifEmpty { "Android" }

}
