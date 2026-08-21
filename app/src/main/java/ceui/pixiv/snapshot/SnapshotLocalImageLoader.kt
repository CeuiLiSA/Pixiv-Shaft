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
        val file = File(SnapshotRepository.root(Shaft.getContext()), snapshotId).resolve(rel)
        if (!file.isFile) return null
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

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        try {
            callback.onDataReady(FileInputStream(file))
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() = Unit

    override fun cancel() = Unit

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}