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
import ceui.pixiv.snapshot.SnapshotAwareGlideUrlLoader;
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
        registry.replace(GlideUrl.class, InputStream.class, new SnapshotAwareGlideUrlLoader.Factory(application.getOkHttpClient()));
        registry.append(GlideUrl.class, File.class, new SnapshotLocalFileLoader.Factory());
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
