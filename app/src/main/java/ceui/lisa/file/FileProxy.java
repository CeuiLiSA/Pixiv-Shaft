package ceui.lisa.file;


import android.content.Context;

import java.io.File;

import ceui.lisa.models.IllustsBean;

public interface FileProxy {

    File imageCacheFolder(Context context);

    File gifCacheFolder(Context context);

    File gifZipFile(Context context, IllustsBean illust);

    File gifUnzipFolder(Context context, IllustsBean illust);

    File gifResultFile(Context context, IllustsBean illust);
}
