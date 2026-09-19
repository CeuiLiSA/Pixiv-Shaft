package ceui.lisa.core;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.Excludes;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.File;
import java.io.InputStream;

import ceui.lisa.activities.Shaft;
import ceui.pixiv.cache.ImageCacheQuota;
import ceui.pixiv.cache.ImageCacheQuotaState;
import ceui.pixiv.snapshot.SnapshotLocalStreamLoader;
import ceui.pixiv.snapshot.SnapshotLocalFileLoader;
import ceui.pixiv.sticker.LocalSticker;
import ceui.pixiv.sticker.StickerModelLoader;

@GlideModule
@Excludes(com.bumptech.glide.integration.okhttp3.OkHttpLibraryGlideModule.class)
public class GlideConfiguration extends AppGlideModule {

    @Override
    public void applyOptions(Context context, GlideBuilder builder) {
        // 图片缓存「预期上限」（issue #1120）。Glide 没有运行时改 maxSize 的公开 API：DiskCache
        // 接口只有 get/put/delete/clear，Glide 对外只暴露 clearDiskCache()，而 DiskLruCacheWrapper
        // 里那个能 setMaxSize 的 DiskLruCache 实例是 private 字段 —— 要拿到它就得把 wrapper 整份
        // fork 进本仓自己维护，跟这个需求的体量不成比例。所以这里只在 Glide 初始化时读一次设置，
        // 改设置后提示重启（与图片加速代理同款限制）。
        //
        // 默认 250 MB == DiskCache.Factory.DEFAULT_DISK_CACHE_SIZE，不配置时行为与历史一致。
        // 淘汰不归我们管：Glide 的 DiskLruCache 自带 LRU（LinkedHashMap accessOrder=true），
        // completeEdit 里 size > maxSize 就 trimToSize 从 LRU 尾部逐个删，我们一行都不写。
        int limitMb = Shaft.sSettings != null
                ? Shaft.sSettings.getImageCacheMaxMb()
                : ImageCacheQuota.DEFAULT_LIMIT_MB;
        // 记下这次真正生效的值：设置页靠它判断「改过但还没重启」，给数值加「未生效」后缀。
        ImageCacheQuotaState.markApplied(limitMb);
        builder.setDiskCache(new InternalCacheDiskCacheFactory(
                context, ImageCacheQuota.maxBytesForLimit(limitMb)));
    }

    @Override
    public void registerComponents(Context context, Glide glide, Registry registry) {
        Shaft application = (Shaft) context.getApplicationContext();
        //Glide 底层默认使用 HttpConnection 进行网络请求,这里替换为 Okhttp 后才能使用本框架,进行 Glide 的加载进度监听。
        //用 LeakSafeOkHttpUrlLoader 而不是官方 OkHttpUrlLoader:官方 fetcher 在
        //「响应已到达、请求随后被取消」(列表快速滑动)时会遗弃打开的 response body,
        //刷屏 "A connection to https://i.pximg.net/ was leaked",详见该类注释。
        registry.replace(GlideUrl.class, InputStream.class, new LeakSafeOkHttpUrlLoader.Factory(application.getOkHttpClient()));
        //「离线快照」的 shaftsnap:// 走本地文件。必须 prepend 到网络 loader 之前:
        //Glide 按注册顺序问 handles(),而 LeakSafeOkHttpUrlLoader.handles() 恒为 true,谁在前谁接管。
        //也必须放在上面那行 replace 之后 —— replace 会先清掉该 (GlideUrl, InputStream) 下已注册的全部条目。
        //普通图片加载只多一次前缀比较(SnapshotLocalStreamLoader.handles),不进快照逻辑、不碰磁盘。
        registry.prepend(GlideUrl.class, InputStream.class, new SnapshotLocalStreamLoader.Factory());
        registry.prepend(GlideUrl.class, File.class, new SnapshotLocalFileLoader.Factory());
        //贴纸走本地文件,fetcher 从 ServicesProvider 按需取 StickerRepository(见该类注释)。
        registry.append(LocalSticker.class, InputStream.class, new StickerModelLoader.Factory(application));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
