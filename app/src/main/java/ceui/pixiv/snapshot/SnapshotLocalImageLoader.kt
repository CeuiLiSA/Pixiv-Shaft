package ceui.pixiv.snapshot

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * `shaftsnap://<snapshotId>/<relative>` → 直读私有快照库里的本地文件，零网络。
 *
 * 注册方式见 `GlideConfiguration`：**prepend 到 [ceui.lisa.core.LeakSafeOkHttpUrlLoader]
 * 之前**，靠 [handles] 只认快照 scheme。这样普通图片加载在 handles() 那一步就被判掉，
 * 不进本类任何逻辑，也不改变原来的网络加载链路——网络 loader 仍以它自己的 Factory
 * 注册在 registry 里（teardown / 后续 replace 都还找得到它）。
 *
 * 不要退回「包一层、handles() 恒 true、内部转发给网络 loader」的写法：那等于替 Glide
 * 决定了谁能处理什么，被包的那个 loader 自己的 handles() 再也不会被问到。
 */
class SnapshotLocalStreamLoader : ModelLoader<GlideUrl, InputStream> {

    // 每次图片加载都会问一次；只做一次前缀比较，不解析、不碰磁盘。
    override fun handles(model: GlideUrl): Boolean =
        model.toStringUrl().startsWith(SNAPSHOT_LOCAL_URL_PREFIX)

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<InputStream>? {
        val (snapshotId, rel) = parseSnapshotLocalUrl(model.toStringUrl()) ?: return null
        // 文件解析(两次 stat)留到 fetcher 真正开始取数时做：buildLoadData 是每个候选 loader
        // 都要走的一步,不该在这里做 IO;而且这里返回 null 会让 shaftsnap:// 掉进网络 loader,
        // 白发一次注定失败的请求 —— 文件缺失该走 onLoadFailed。
        return ModelLoader.LoadData(model, SnapshotLocalStreamFetcher(snapshotId, rel))
    }

    class Factory : ModelLoaderFactory<GlideUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> =
            SnapshotLocalStreamLoader()

        override fun teardown() = Unit
    }
}

private class SnapshotLocalStreamFetcher(
    private val snapshotId: String,
    private val rel: String,
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
            val file = snapshotAssetFile(snapshotId, rel)
                ?: throw FileNotFoundException("快照资源不存在: $snapshotId/$rel")
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
