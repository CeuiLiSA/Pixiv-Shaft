package ceui.lisa.core;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.Excludes;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.File;
import java.io.InputStream;

import ceui.lisa.activities.Shaft;
import ceui.pixiv.snapshot.SnapshotLocalStreamLoader;
import ceui.pixiv.snapshot.SnapshotLocalFileLoader;

@GlideModule
@Excludes(com.bumptech.glide.integration.okhttp3.OkHttpLibraryGlideModule.class)
public class GlideConfiguration extends AppGlideModule {

    @Override
    public void applyOptions(Context context, GlideBuilder builder) {

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
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
