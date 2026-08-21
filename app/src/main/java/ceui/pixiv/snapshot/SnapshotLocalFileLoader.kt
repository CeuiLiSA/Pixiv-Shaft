package ceui.pixiv.snapshot

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import ceui.lisa.activities.Shaft
import java.io.File

/**
 * Glide `asFile()` 用的本地快照加载器：让 ImageLoaderV3 / 现有大图查看器可以直接把
 * `shaftsnap://` 当作本地文件消费，不需要自研图片页。
 */
class SnapshotLocalFileLoader : ModelLoader<GlideUrl, File> {

    override fun handles(model: GlideUrl): Boolean = parseSnapshotLocalUrl(model.toStringUrl()) != null

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<File>? {
        val parsed = parseSnapshotLocalUrl(model.toStringUrl()) ?: return null
        val (snapshotId, rel) = parsed
        val file = runCatching {
            safeResolve(SnapshotRepository.root(Shaft.getContext()), "$snapshotId/$rel")
        }.getOrNull()?.takeIf { it.isFile } ?: return null
        return ModelLoader.LoadData(model, object : DataFetcher<File> {
            override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in File>) {
                callback.onDataReady(file)
            }

            override fun cleanup() = Unit
            override fun cancel() = Unit
            override fun getDataClass(): Class<File> = File::class.java
            override fun getDataSource(): DataSource = DataSource.LOCAL
        })
    }

    class Factory : ModelLoaderFactory<GlideUrl, File> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, File> =
            SnapshotLocalFileLoader()

        override fun teardown() = Unit
    }
}