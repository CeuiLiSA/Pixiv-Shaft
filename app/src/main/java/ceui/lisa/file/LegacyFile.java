package ceui.lisa.file;

import android.content.Context;

import java.io.File;

import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

import static android.os.Environment.DIRECTORY_PICTURES;
import static ceui.lisa.utils.Settings.FILE_PATH_SINGLE_R18;

public class LegacyFile implements FileProxy {

    private static final String GIF_CACHE = "/gif cache";
    private static final String IMAGE_CACHE = "/image_manager_disk_cache";

    @Override
    public File imageCacheFolder(Context context) {
        File cacheDir = new File(context.getCacheDir().getPath() + IMAGE_CACHE);
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }
        Common.showLog("LegacyFile imageCacheFolder " + cacheDir.getPath());
        return cacheDir;
    }

    @Override
    public File gifCacheFolder(Context context) {
        File cacheDir = new File(context.getExternalCacheDir().getPath() + GIF_CACHE);
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }
        Common.showLog("LegacyFile gifCacheFolder " + cacheDir.getPath());
        return cacheDir;
    }

    @Override
    public File gifZipFile(Context context, IllustsBean illust) {
        File gifCacheFolder = gifCacheFolder(context);
        String zipName = new FileName().zipName(illust);
        File zipFile = new File(gifCacheFolder, zipName);
        if (!zipFile.exists()) {
            try {
                zipFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Common.showLog("LegacyFile gifZipFile " + zipFile.getPath());
        return zipFile;
    }

    @Override
    public File gifUnzipFolder(Context context, IllustsBean illust) {
        String folderName = new FileName().unzipName(illust);
        File unzipDirFile = new File(gifCacheFolder(context).getPath() + "/" + folderName);
        if (!unzipDirFile.exists()) {
            unzipDirFile.mkdir();
        }
        Common.showLog("LegacyFile gifUnzipFolder " + unzipDirFile.getPath());
        return unzipDirFile;
    }

    @Override
    public File gifResultFile(Context context, IllustsBean illust) {
        File gifCacheFolder = gifCacheFolder(context);
        String gifResultName = new FileName().gifName(illust);
        File gifResult = new File(gifCacheFolder, gifResultName);
        if (!gifResult.exists()) {
            try {
                gifResult.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Common.showLog("LegacyFile gifResultFile " + gifResult.getPath());
        return gifResult;
    }

    @Override
    public File textFile(Context context, String name) {
        File gifCacheFolder = gifCacheFolder(context);
        File textFile = new File(gifCacheFolder, name);
        if (!textFile.exists()) {
            try {
                textFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return textFile;
    }
}
