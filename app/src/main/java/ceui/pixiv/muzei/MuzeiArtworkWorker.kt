package ceui.pixiv.muzei

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ceui.lisa.activities.Shaft
import ceui.lisa.download.IllustDownload
import ceui.pixiv.api.Client
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.pixiv.session.SessionManager
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * 给 Muzei 拉一批作品(issue #548)。由 [ShaftMuzeiArtProvider.onLoadRequested] 和设置页触发,
 * 只负责「pixiv API → Artwork 元数据」;图片二进制由 Muzei 在需要时通过
 * [ShaftMuzeiArtProvider.openFile] 按需下载,这里不预取。
 */
class MuzeiArtworkWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (SessionManager.getBearerTokenOrEmpty().isEmpty()) {
            // 没登录拉不到任何榜单;不 retry,等用户登录后设置页会重新触发
            Timber.tag(TAG).i("skip: not logged in")
            return Result.failure()
        }
        val source = MuzeiPrefs.source
        val allowR18 = MuzeiPrefs.allowR18
        val illusts = try {
            withContext(Dispatchers.IO) { fetch(source) }
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "fetch %s failed (io)", source)
            return Result.retry()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "fetch %s failed", source)
            return Result.failure()
        }

        val artworks = illusts.asSequence()
            .filter { it.isWallpaperCandidate(allowR18) }
            .distinctBy { it.id }
            .mapNotNull { it.toArtwork() }
            .take(MAX_BATCH)
            .toList()
        if (artworks.isEmpty()) {
            Timber.tag(TAG).w("no usable artwork from %s (allowR18=%s)", source, allowR18)
            return Result.failure()
        }

        val client = ProviderContract.getProviderClient(
            applicationContext, MuzeiPrefs.authority(applicationContext)
        )
        val replace = inputData.getBoolean(MuzeiPrefs.INPUT_REPLACE, false)
        if (replace) client.setArtwork(artworks) else client.addArtwork(artworks)
        Timber.tag(TAG).i("pushed %d artworks from %s replace=%s", artworks.size, source, replace)
        return Result.success()
    }

    private suspend fun fetch(source: MuzeiSource): List<Illust> = when (source) {
        MuzeiSource.DAILY_RANK -> Client.appApi.getRank("day", null).illusts.orEmpty()
        // 推荐流每次都不同,打乱一下避免总是同一批头图先被轮到
        MuzeiSource.RECOMMEND -> Client.appApi.getRecmdIllust(true).illusts.orEmpty().shuffled()
        MuzeiSource.BOOKMARKS -> {
            val uid = SessionManager.loggedInUid
            if (uid <= 0L) emptyList()
            else Client.appApi.getUserLikeIllust(uid, "public").illusts.orEmpty().shuffled()
        }
        MuzeiSource.WALLPAPER_RANK -> ShaftApiV2Client.service
            .wallpapers(screen = "phone", limit = MAX_BATCH)
            .items
            .mapNotNull { item ->
                val json = item.bean ?: return@mapNotNull null
                try {
                    Shaft.sGson.fromJson(json, Illust::class.java)
                } catch (e: Throwable) {
                    Timber.tag(TAG).w(e, "skip malformed bean id=%d", item.target_id)
                    null
                }
            }
    }

    private fun Illust.isWallpaperCandidate(allowR18: Boolean): Boolean {
        // 动图 original 是 zip,漫画多为分镜长条,都不适合当壁纸
        if (isGif() || type == "manga") return false
        if (!allowR18 && (isR18File() || isSensitive())) return false
        return true
    }

    private fun Illust.toArtwork(): Artwork? {
        val original = IllustDownload.getUrl(this, 0, Params.IMAGE_RESOLUTION_ORIGINAL) ?: return null
        return Artwork(
            token = id.toString(),
            title = title?.takeIf { it.isNotBlank() } ?: "pixiv #$id",
            byline = user?.name,
            attribution = "pixiv",
            persistentUri = original.toUri(),
            webUri = "https://www.pixiv.net/artworks/$id".toUri(),
            metadata = user?.id?.toString(),
        )
    }

    companion object {
        private const val TAG = "MuzeiWorker"
        private const val MAX_BATCH = 30
    }
}
