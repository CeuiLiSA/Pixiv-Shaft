package ceui.lisa.update

import ceui.lisa.BuildConfig
import ceui.lisa.http.GithubProxy
import ceui.lisa.http.Retro
import com.google.gson.GsonBuilder
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import ceui.lisa.R
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class RateLimitException(
    val resetEpochSeconds: Long? = null,
    message: String = "被速率限制，请稍后再试",
    cause: Throwable? = null
) : IOException(message, cause)

object AppUpdateChecker {

    private const val KEY_LAST_CHECK_TIME = "update_last_check_time"
    private const val KEY_SKIPPED_VERSION = "update_skipped_version"
    private const val KEY_DOWNLOAD_ID = "update_download_id"
    private const val KEY_DOWNLOAD_TAG = "update_download_tag"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val api: GitHubApi by lazy {
        val client = Retro.getLogClient()
            .addInterceptor { chain ->
                val original = chain.request()
                // 从 GitHub 拉取的请求统一在这里插一次加速前缀（「不使用」时原样返回，见 GithubProxy）。
                // 放在拦截器而不是改 BASE_URL：baseUrl 是构建期定死的，用户改完加速地址就得
                // 重建这个 Retrofit；拦截器每次请求现读设置，改完立刻生效。
                val request = original.newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .url(GithubProxy.wrap(original.url))
                    .build()
                val response = chain.proceed(request)
                if (isRateLimitResponse(response)) {
                    val reset = response.header("x-ratelimit-reset")?.toLongOrNull()
                    // 不交回 response 就得自己关，否则这条连接永远回不了连接池。
                    response.close()
                    throw RateLimitException(resetEpochSeconds = reset)
                }
                response
            }
            .build()
        Retrofit.Builder()
            .baseUrl(GitHubApi.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
            .build()
            .create(GitHubApi::class.java)
    }

    internal fun isRateLimitResponse(response: Response): Boolean {
        if (response.code == 429) return true
        if (response.code == 403) {
            val remaining = response.header("x-ratelimit-remaining")?.toIntOrNull()
            if (remaining == 0) return true
            if (!response.header("retry-after").isNullOrBlank()) return true
            val peek = runCatching { response.peekBody(1024).string() }.getOrNull()
            if (peek?.contains("rate limit", ignoreCase = true) == true) return true
        }
        return false
    }

    fun isRateLimit(throwable: Throwable): Boolean {
        if (throwable is RateLimitException) return true
        if (throwable is HttpException) {
            val response = throwable.response()
            if (response != null) {
                if (response.code() == 429) return true
                if (response.code() == 403) {
                    val remaining = response.headers()["x-ratelimit-remaining"]?.toIntOrNull()
                    if (remaining == 0) return true
                    if (!response.headers()["retry-after"].isNullOrBlank()) return true
                }
            }
            val msg = throwable.message()
            if (msg.contains("rate limit", ignoreCase = true)) return true
        }
        val message = throwable.message
        if (message != null && message.contains("rate limit", ignoreCase = true)) return true
        val cause = throwable.cause
        if (cause != null && isRateLimit(cause)) return true
        return false
    }

    fun extractResetEpochSeconds(throwable: Throwable): Long? {
        if (throwable is RateLimitException) {
            return throwable.resetEpochSeconds
        }
        if (throwable is HttpException) {
            val header = throwable.response()?.headers()?.get("x-ratelimit-reset")
            return header?.toLongOrNull()
        }
        val cause = throwable.cause
        return if (cause != null) extractResetEpochSeconds(cause) else null
    }

    fun formatResetTime(resetEpochSeconds: Long, nowMs: Long = System.currentTimeMillis()): String {
        val resetMs = resetEpochSeconds * 1000L
        val resetCal = Calendar.getInstance().apply { timeInMillis = resetMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val isSameDay = resetCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                resetCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(resetMs))
        return if (isSameDay) {
            timeStr
        } else {
            val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            dateFormat.format(Date(resetMs))
        }
    }

    fun getRateLimitMessage(
        context: Context,
        resetEpochSeconds: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        if (resetEpochSeconds != null && resetEpochSeconds * 1000L > nowMs) {
            val timeStr = formatResetTime(resetEpochSeconds, nowMs)
            return context.getString(R.string.update_rate_limited_with_reset, timeStr)
        }
        return context.getString(R.string.update_rate_limited)
    }

    fun getRateLimitMessage(context: Context, throwable: Throwable): String {
        val reset = extractResetEpochSeconds(throwable)
        return getRateLimitMessage(context, reset)
    }

    suspend fun fetchAllReleases(): List<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            api.getReleases(GitHubApi.OWNER, GitHubApi.REPO)
        } catch (e: Exception) {
            if (isRateLimit(e)) {
                val reset = extractResetEpochSeconds(e)
                throw RateLimitException(resetEpochSeconds = reset, cause = e)
            } else {
                throw e
            }
        }
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        val release = try {
            api.getLatestRelease(GitHubApi.OWNER, GitHubApi.REPO)
        } catch (e: Exception) {
            if (isRateLimit(e)) {
                val reset = extractResetEpochSeconds(e)
                throw RateLimitException(resetEpochSeconds = reset, cause = e)
            } else {
                throw e
            }
        }
        val remoteVersion = release.tagName.removePrefix("v").removePrefix("V")
        val currentVersion = BuildConfig.VERSION_NAME
        if (isNewerVersion(remoteVersion, currentVersion)) {
            UpdateResult.UpdateAvailable(release)
        } else {
            UpdateResult.NoUpdate(remoteVersion)
        }
    }

    fun shouldAutoCheck(): Boolean {
        if (BuildConfig.IS_LITE) return false
        val mmkv = MMKV.defaultMMKV()
        val lastCheck = mmkv.decodeLong(KEY_LAST_CHECK_TIME, 0L)
        return System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS
    }

    fun markChecked() {
        MMKV.defaultMMKV().encode(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
    }

    fun skipVersion(version: String) {
        MMKV.defaultMMKV().encode(KEY_SKIPPED_VERSION, version)
    }

    fun isVersionSkipped(version: String): Boolean {
        return MMKV.defaultMMKV().decodeString(KEY_SKIPPED_VERSION, "") == version
    }

    fun saveOngoingDownload(downloadId: Long, versionTag: String) {
        MMKV.defaultMMKV().apply {
            encode(KEY_DOWNLOAD_ID, downloadId)
            encode(KEY_DOWNLOAD_TAG, versionTag)
        }
    }

    fun clearOngoingDownload() {
        MMKV.defaultMMKV().apply {
            removeValueForKey(KEY_DOWNLOAD_ID)
            removeValueForKey(KEY_DOWNLOAD_TAG)
        }
    }

    fun getOngoingDownloadId(versionTag: String): Long {
        val mmkv = MMKV.defaultMMKV()
        val savedTag = mmkv.decodeString(KEY_DOWNLOAD_TAG, "")
        if (savedTag != versionTag) return -1L
        return mmkv.decodeLong(KEY_DOWNLOAD_ID, -1L)
    }

    fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    fun findApkAsset(release: GitHubRelease): GitHubAsset? {
        val assets = release.assets ?: return null
        return assets.firstOrNull {
            it.name.endsWith(".apk") && it.name.contains("github", ignoreCase = true)
        } ?: assets.firstOrNull {
            it.name.endsWith(".apk") && it.name.contains("release", ignoreCase = true)
        } ?: assets.firstOrNull {
            it.name.endsWith(".apk")
        }
    }

    sealed class UpdateResult {
        data class UpdateAvailable(val release: GitHubRelease) : UpdateResult()
        data class NoUpdate(val remoteVersion: String) : UpdateResult()
    }
}
