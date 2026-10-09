package ceui.lisa.update

import ceui.lisa.BuildConfig
import ceui.lisa.http.GithubProxy
import ceui.lisa.http.Retro
import com.google.gson.GsonBuilder
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import kotlinx.coroutines.CancellationException
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber

object AppUpdateChecker {

    private const val KEY_LAST_CHECK_TIME = "update_last_check_time"
    private const val KEY_SKIPPED_VERSION = "update_skipped_version"
    private const val KEY_DOWNLOAD_ID = "update_download_id"
    private const val KEY_DOWNLOAD_TAG = "update_download_tag"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    @androidx.annotation.VisibleForTesting
    internal var apiOverride: GitHubApi? = null

    internal val api: GitHubApi
        get() = apiOverride ?: defaultApi

    private val defaultApi: GitHubApi by lazy {
        val client = Retro.getLogClient()
            .addInterceptor { chain ->
                val original = chain.request()
                // 从 GitHub 拉取的请求统一在这里插一次加速前缀（「不使用」时原样返回，见 GithubProxy）。
                // 放在拦截器而不是改 BASE_URL：baseUrl 是构建期定死的，用户改完加速地址就得
                // 重建这个 Retrofit；拦截器每次请求现读设置，改完立刻生效。
                val isAtomFeed = original.url.encodedPath.endsWith(".atom")
                val targetUrl = if (isAtomFeed) {
                    // 主流 GitHub 加速反代（gh-proxy 等）均不支持 /releases.atom（直接返回 404）；
                    // 若有直接请求 atom 的场景，保持原 URL 直连，不拼加速前缀。
                    original.url
                } else {
                    GithubProxy.wrap(original.url)
                }
                val requestBuilder = original.newBuilder()
                    .url(targetUrl)
                if (original.header("Accept") == null) {
                    requestBuilder.header("Accept", "application/vnd.github+json")
                }
                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .build()
        Retrofit.Builder()
            .baseUrl(GitHubApi.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
            .build()
            .create(GitHubApi::class.java)
    }

    internal suspend fun fetchReleasesFromFeed(
        owner: String = GitHubApi.OWNER,
        repo: String = GitHubApi.REPO
    ): List<GitHubRelease> = withContext(Dispatchers.IO) {
        val response = api.getReleasesAtom(owner, repo)
        response.use { body ->
            GitHubFeedParser.parse(body.byteStream(), owner, repo)
        }
    }

    /**
     * 版本历史只走 API：Atom 订阅固定只给最近 10 条，且没有 APK 体积、正文是有损的 HTML 转写，
     * 拿它当历史会把一百来条版本截成九条。历史页是用户手动进的，不在每日自动检查的配额压力里。
     */
    suspend fun fetchAllReleases(): List<GitHubRelease> = withContext(Dispatchers.IO) {
        api.getReleases(GitHubApi.OWNER, GitHubApi.REPO)
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        // 使用 GitHub 加速代理时始终降级走 API 端点（反代均不支持 /releases.atom）；
        // 未开启加速代理时，优先通过 RSS Atom Feeds 检查更新，免除 GitHub API 未鉴权每小时 60 次的 Rate Limit 限制。
        if (!GithubProxy.isEnabled()) {
            try {
                val feedReleases = fetchReleasesFromFeed()
                val latestRelease = feedReleases.firstOrNull { isVersionTag(it.tagName) }
                if (latestRelease != null) {
                    val remoteVersion = latestRelease.tagName.removePrefix("v").removePrefix("V")
                    val currentVersion = BuildConfig.VERSION_NAME
                    return@withContext if (isNewerVersion(remoteVersion, currentVersion)) {
                        UpdateResult.UpdateAvailable(fetchFullRelease(latestRelease))
                    } else {
                        UpdateResult.NoUpdate(remoteVersion)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Check update via RSS Atom feed failed, falling back to API endpoint")
            }
        }

        // 降级：走原有的 GitHub API 端点
        val release = api.getLatestRelease(GitHubApi.OWNER, GitHubApi.REPO)
        val remoteVersion = release.tagName.removePrefix("v").removePrefix("V")
        val currentVersion = BuildConfig.VERSION_NAME
        if (isNewerVersion(remoteVersion, currentVersion)) {
            UpdateResult.UpdateAvailable(release)
        } else {
            UpdateResult.NoUpdate(remoteVersion)
        }
    }

    /**
     * Atom 只负责「有没有新版」这一问；真有新版时再按 tag 向 API 要一次完整 release。
     *
     * 订阅里合成的 APK 资产 size 恒为 0，而 [UpdateBottomSheet.isApkComplete] 靠 size 拦截
     * 被 OEM DownloadManager 截断却标成成功的安装包 —— size 为 0 时这道校验直接放行。
     * 正文也换回作者写的原始 Markdown。只在确有新版时多这一次请求，不会把限流问题带回来；
     * 这一次也失败（含限流）就退回订阅合成的版本，至少更新提示还在。
     */
    private suspend fun fetchFullRelease(feedRelease: GitHubRelease): GitHubRelease = try {
        api.getReleaseByTag(GitHubApi.OWNER, GitHubApi.REPO, feedRelease.tagName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Fetch full release ${feedRelease.tagName} failed, using feed release")
        feedRelease
    }

    fun isVersionTag(tag: String): Boolean = GitHubFeedParser.isVersionTag(tag)

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
