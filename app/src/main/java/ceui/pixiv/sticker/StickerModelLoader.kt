package ceui.pixiv.sticker

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

/** Only LocalSticker models are accepted. No GlideUrl delegation or remote fallback. */
class StickerModelLoader : ModelLoader<LocalSticker, InputStream> {
    override fun handles(model: LocalSticker) = true
    override fun buildLoadData(model: LocalSticker, width: Int, height: Int, options: Options) =
        ModelLoader.LoadData(ObjectKey("${model.generation}:${model.stickerId}:${model.resourceSize}"), Fetcher(model))

    class Factory : ModelLoaderFactory<LocalSticker, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory) = StickerModelLoader()
        override fun teardown() = Unit
    }

    private class Fetcher(private val model: LocalSticker) : DataFetcher<InputStream> {
        private var stream: InputStream? = null
        private var cancelled = false
        @Synchronized
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            if (cancelled) return
            try {
                stream = StickerRepository.localFile(model).inputStream()
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
