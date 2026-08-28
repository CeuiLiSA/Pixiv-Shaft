package ceui.pixiv.muzei

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteActionCompat
import androidx.core.graphics.drawable.IconCompat
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.http.ImageHostManager
import ceui.lisa.utils.Params
import ceui.pixiv.widget.WidgetBookmarkReceiver
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Muzei 壁纸来源(issue #548)。Muzei 通过 ContentProvider 协议驱动:
 *  - [onLoadRequested]:Muzei 快把现有图轮完 / 用户手动「下一张」时叫,这里只排 WorkManager,
 *    不在 provider 线程碰网络(官方强烈建议,Muzei 可能在离线时调用)。
 *  - [openFile]:Muzei 首次展示某张图时拉二进制。pixiv 图床要 Referer,而且要按用户选的
 *    图片 host 重写 + 复用 app 的直连 client,否则默认实现裸 HttpURLConnection 直打 i.pximg.net 必 403/被墙。
 *  - [getCommandActions]:壁纸页底部的动作 ——「在 Shaft 中查看」「收藏」。
 */
class ShaftMuzeiArtProvider : MuzeiArtProvider() {

    override fun onLoadRequested(initial: Boolean) {
        val context = context ?: return
        // initial = Muzei 首次选中本来源,此时没有任何图,replace 与否无差别
        MuzeiPrefs.enqueueLoad(context, replace = false)
    }

    override fun getDescription(): String {
        val context = context ?: return super.getDescription()
        return context.getString(MuzeiPrefs.source.labelRes)
    }

    @Throws(IOException::class)
    override fun openFile(artwork: Artwork): InputStream {
        val persistentUri = artwork.persistentUri
            ?: throw IllegalStateException("artwork ${artwork.token} has no persistentUri")
        val url = ImageHostManager.rewrite(persistentUri.toString())
        val request = Request.Builder()
            .url(url)
            .header(Params.MAP_KEY, Params.IMAGE_REFERER)
            .header(Params.USER_AGENT, Params.PHONE_MODEL)
            .build()
        val response = imageClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            // 4xx 大概率是作品已删除/私密:抛非 IOException 让 Muzei 走 onInvalidArtwork 剔掉,不反复重试
            if (response.code in 400..499) {
                throw IllegalStateException("HTTP ${response.code} for ${artwork.token}")
            }
            throw IOException("HTTP ${response.code} for ${artwork.token}")
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("empty body for ${artwork.token}")
        }
        return body.byteStream()
    }

    override fun getArtworkInfo(artwork: Artwork): PendingIntent? {
        val context = context ?: return null
        return openInShaftPendingIntent(context, artwork, requestCode = 0)
    }

    override fun getCommandActions(artwork: Artwork): List<RemoteActionCompat> {
        val context = context ?: return super.getCommandActions(artwork)
        return listOfNotNull(
            createOpenInShaftAction(context, artwork),
            createBookmarkAction(context, artwork),
        )
    }

    private fun createOpenInShaftAction(context: Context, artwork: Artwork): RemoteActionCompat? {
        val pi = openInShaftPendingIntent(context, artwork, requestCode = 1) ?: return null
        val title = context.getString(R.string.muzei_action_open_in_shaft)
        return RemoteActionCompat(
            IconCompat.createWithResource(context, R.drawable.ic_baseline_launch_24),
            title, title, pi,
        )
    }

    private fun createBookmarkAction(context: Context, artwork: Artwork): RemoteActionCompat? {
        val illustId = artwork.token?.toLongOrNull() ?: return null
        if (illustId <= 0L || illustId > Int.MAX_VALUE) return null
        val title = context.getString(R.string.muzei_action_bookmark)
        return RemoteActionCompat(
            IconCompat.createWithResource(context, R.drawable.ic_favorite_border_black_24dp),
            title, title,
            // slot 必须带作品 id:receiver 的 PendingIntent 按 data URI 区分,固定 slot 会让所有
            // 作品共用一个 PI,FLAG_UPDATE_CURRENT 把 extras 覆盖成最后一次查询的那张 —— 用户在
            // 壁纸 A 上点收藏,收藏到的却是 Muzei 刚预取过命令的 B。
            WidgetBookmarkReceiver.pendingIntent(context, "muzei/$illustId", illustId.toInt()),
        )
    }

    /**
     * 用 pixiv 作品链接 + setPackage 钉死到 Shaft 自己(OutWakeActivity 已声明 /artworks/ 的
     * intent-filter),避免弹系统选择器 / 被浏览器接走。
     */
    private fun openInShaftPendingIntent(context: Context, artwork: Artwork, requestCode: Int): PendingIntent? {
        val webUri = artwork.webUri ?: return null
        val intent = Intent(Intent.ACTION_VIEW, webUri).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onInvalidArtwork(artwork: Artwork) {
        Timber.tag(TAG).i("invalid artwork dropped token=%s", artwork.token)
        super.onInvalidArtwork(artwork)
    }

    companion object {
        private const val TAG = "MuzeiProvider"

        /**
         * 复用 app 的图片 client(直连 HttpDns / 无 SNI / 代理 host 都在里面),只放宽读超时:
         * 原图动辄 10MB+,Muzei 是后台慢慢拉,不必卡 Glide 的默认超时。
         */
        private val imageClient: OkHttpClient by lazy {
            (Shaft.getContext() as Shaft).okHttpClient.newBuilder()
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}
