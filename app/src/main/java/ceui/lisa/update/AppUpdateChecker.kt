package ceui.lisa.update

import ceui.lisa.BuildConfig
import ceui.lisa.http.Retro
import com.google.gson.GsonBuilder
import com.tencent.mmkv.MMKV
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

object AppUpdateChecker {

    private const val KEY_LAST_CHECK_TIME = "update_last_check_time"
    private const val KEY_SKIPPED_VERSION = "update_skipped_version"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val api: GitHubApi by lazy {
        val client = Retro.getLogClient()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .build()
                chain.proceed(request)
            }
            .build()
        Retrofit.Builder()
            .baseUrl(GitHubApi.BASE_URL)
            .client(client)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
            .build()
            .create(GitHubApi::class.java)
    }

    fun fetchAllReleases(): Observable<List<GitHubRelease>> {
        return api.getReleases(GitHubApi.OWNER, GitHubApi.REPO)
    }

    fun checkForUpdate(): Observable<UpdateResult> {
        return api.getLatestRelease(GitHubApi.OWNER, GitHubApi.REPO)
            .map { release ->
                val remoteVersion = release.tagName.removePrefix("v").removePrefix("V")
                val currentVersion = BuildConfig.VERSION_NAME
                if (isNewerVersion(remoteVersion, currentVersion)) {
                    UpdateResult.UpdateAvailable(release)
                } else {
                    UpdateResult.NoUpdate(remoteVersion)
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun shouldAutoCheck(): Boolean {
        if (BuildConfig.UPDATE_CHANNEL != "github") return false
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
