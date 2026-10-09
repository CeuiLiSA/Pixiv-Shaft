package ceui.pixiv.webdav

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV 每日自动备份。只在不按流量计费的网络 + 电量不低时跑：带浏览记录的备份可能有几十 MB，
 * 不该悄悄吃用户的移动流量。内容和上次上传的一样时跳过，不在远端堆重复文件。
 */
class WebDavBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = WebDavPrefs.load()
        if (!WebDavPrefs.autoBackup || !config.isComplete) {
            // 配置被清空 / 密码在 Keystore 丢失后解不开：重试也没用，等用户回设置页处理。
            Timber.tag(TAG).w("[auto] skipped: enabled=%b complete=%b", WebDavPrefs.autoBackup, config.isComplete)
            return Result.failure()
        }
        return try {
            val result = WebDavBackup.backup(
                applicationContext,
                config,
                includeHistory = WebDavPrefs.includeHistory,
                skipIfUnchanged = true,
            )
            if (result is WebDavBackup.BackupResult.Uploaded) {
                WebDavPrefs.recordRun(WebDavLastRun(System.currentTimeMillis(), true, result.name))
            }
            Result.success()
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "[auto] backup failed")
            WebDavPrefs.recordRun(WebDavLastRun(System.currentTimeMillis(), false, e.message ?: e.javaClass.simpleName))
            // 账号密码错 / 地址不存在属于配置问题，退避重试只会反复失败；网络类错误交给 WorkManager 重试。
            val permanent = e is WebDavException && e.kind != WebDavException.Kind.HTTP
            if (permanent || runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "WebDav"
        private const val WORK_NAME = "webdav_auto_backup"
        private const val MAX_ATTEMPTS = 3

        /** 开关打开时排期（已排期则保持原周期），关掉时取消。 */
        @JvmStatic
        fun sync(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!WebDavPrefs.autoBackup) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
