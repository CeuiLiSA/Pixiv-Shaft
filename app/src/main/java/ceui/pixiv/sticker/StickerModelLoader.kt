package ceui.pixiv.sticker

import android.content.Context
import ceui.pixiv.services.appServices
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

/**
 * Only LocalSticker models are accepted. No GlideUrl delegation or remote fallback.
 *
 * [repository] 是**取用函数**而不是实例：Glide 的 registry 在首次加载图片时才装配，
 * 但 Factory 本身在 `GlideConfiguration.registerComponents` 里就构造了；用函数取，
 * 这里就不对「服务已经建好了没」有任何时序假设，单测也不必造一个 Application。
 */
class StickerModelLoader(private val repository: () -> StickerRepository) : ModelLoader<LocalSticker, InputStream> {
    override fun handles(model: LocalSticker) = true
    override fun buildLoadData(model: LocalSticker, width: Int, height: Int, options: Options) =
        ModelLoader.LoadData(ObjectKey("${model.generation}:${model.stickerId}:${model.resourceSize}"), Fetcher(repository, model))

    class Factory(private val context: Context) : ModelLoaderFactory<LocalSticker, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) =
            StickerModelLoader { context.appServices().stickerRepository }
        override fun teardown() = Unit
    }

    private class Fetcher(
        private val repository: () -> StickerRepository,
        private val model: LocalSticker,
    ) : DataFetcher<InputStream> {
        private var stream: InputStream? = null
        private var cancelled = false
        @Synchronized
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            if (cancelled) return
            try {
                stream = repository().localFile(model).inputStream()
                callback.onDataReady(stream)
            } catch (error: Exception) { callback.onLoadFailed(error) }
        }
        @Synchronized
        override fun cleanup() { stream?.close(); stream = null }
        @Synchronized
        override fun cancel() { cancelled = true; cleanup() }
        override fun getDataClass() = InputStream::class.java
        override fun getDataSource() = DataSource.LOCAL
    }
}
