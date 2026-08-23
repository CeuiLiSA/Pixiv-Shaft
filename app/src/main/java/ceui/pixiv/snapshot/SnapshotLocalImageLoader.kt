package ceui.pixiv.snapshot

import android.net.Uri
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import ceui.lisa.activities.Shaft
import ceui.lisa.core.LeakSafeOkHttpUrlLoader
import okhttp3.Call
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Glide 的统一图片入口：
 * - `shaftsnap://<snapshotId>/<relative>` → 直接读私有快照库里的本地文件，零网络；
 * - 其它 URL → 委托 [LeakSafeOkHttpUrlLoader]，保持现有 Pixiv 加载/进度行为不变。
 */
class SnapshotAwareGlideUrlLoader(
    private val remote: ModelLoader<GlideUrl, InputStream>,
) : ModelLoader<GlideUrl, InputStream> {

    override fun handles(model: GlideUrl): Boolean = true

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<InputStream>? {
        val url = model.toStringUrl()
        val parsed = parseSnapshotLocalUrl(url)
        if (parsed == null) {
            return remote.buildLoadData(model, width, height, options)
        }
        val (snapshotId, rel) = parsed
        val file = runCatching {
            safeResolve(SnapshotRepository.root(Shaft.getContext()), "$snapshotId/$rel")
        }.getOrNull()?.takeIf { it.isFile } ?: return null
        return ModelLoader.LoadData(model, SnapshotLocalStreamFetcher(file))
    }

    class Factory(private val client: Call.Factory) : ModelLoaderFactory<GlideUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> {
            return SnapshotAwareGlideUrlLoader(LeakSafeOkHttpUrlLoader(client))
        }

        override fun teardown() = Unit
    }
}

private class SnapshotLocalStreamFetcher(
    private val file: File,
) : DataFetcher<InputStream> {

    /**
     * 交出去的那条流。Glide 的契约是「fetcher 自己在 [cleanup] 里关掉 loadData 交出的数据」
     * （见官方 LocalUriFetcher / 本仓库 LeakSafeOkHttpStreamFetcher），引擎只保证调 cleanup、
     * 不会替你关。不持有就关不掉：每张快照图漏一个 fd，只能等 FileInputStream 的 finalizer
     * 回收，多页快照反复浏览会刷 StrictMode 的 CloseGuard 告警乃至耗尽 fd。
     */
    private var stream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        try {
            val opened = FileInputStream(file)
            stream = opened
            callback.onDataReady(opened)
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        try {
            stream?.close()
        } catch (_: IOException) {
            // Ignored: 本地文件流,关失败无可挽回也无副作用
        }
        stream = null
    }

    override fun cancel() = Unit

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}