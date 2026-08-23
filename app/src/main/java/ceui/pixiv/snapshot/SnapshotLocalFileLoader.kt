package ceui.pixiv.snapshot

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import java.io.File
import java.io.FileNotFoundException

/**
 * Glide `asFile()` 用的本地快照加载器：让 ImageLoaderV3 / 现有大图查看器可以直接把
 * `shaftsnap://` 当作本地文件消费，不需要自研图片页。
 *
 * 与 [SnapshotLocalStreamLoader] 同样只认快照 scheme，普通 URL 在 handles() 就被判掉。
 */
class SnapshotLocalFileLoader : ModelLoader<GlideUrl, File> {

    override fun handles(model: GlideUrl): Boolean =
        model.toStringUrl().startsWith(SNAPSHOT_LOCAL_URL_PREFIX)

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<File>? {
        val (snapshotId, rel) = parseSnapshotLocalUrl(model.toStringUrl()) ?: return null
        return ModelLoader.LoadData(model, SnapshotLocalFileFetcher(snapshotId, rel))
    }

    class Factory : ModelLoaderFactory<GlideUrl, File> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, File> =
            SnapshotLocalFileLoader()

        override fun teardown() = Unit
    }
}

private class SnapshotLocalFileFetcher(
    private val snapshotId: String,
    private val rel: String,
) : DataFetcher<File> {

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in File>) {
        val file = snapshotAssetFile(snapshotId, rel)
        if (file != null) {
            callback.onDataReady(file)
        } else {
            callback.onLoadFailed(FileNotFoundException("快照资源不存在: $snapshotId/$rel"))
        }
    }

    // 只是把库里现成的文件递出去，没有需要释放的东西。
    override fun cleanup() = Unit

    override fun cancel() = Unit

    override fun getDataClass(): Class<File> = File::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}
