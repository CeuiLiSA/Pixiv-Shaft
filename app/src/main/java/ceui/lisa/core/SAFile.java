package ceui.lisa.core;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import com.blankj.utilcode.util.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

import ceui.lisa.activities.Shaft;
import ceui.lisa.download.FileCreator;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

import static android.provider.DocumentsContract.Document.MIME_TYPE_DIR;

public class SAFile {

    public static DocumentFile getDocument(Context context, IllustsBean illust, int index) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        if (!root.exists() || !root.isDirectory()) {
            Common.showToast("创建文件失败，已授权的下载目录是否被移除?", true);
            return null;
        }
        String id = DocumentsContract.getTreeDocumentId(rootUri);
        String displayName = FileCreator.createIllustFile(illust, index).getName();
        id = id + "/" + displayName;
        Uri childrenUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, id);
        DocumentFile childFile = DocumentFile.fromSingleUri(context, childrenUri);

        if (childFile != null && childFile.exists()) {
            return childFile;
        } else {
            assert root != null;
            return root.createFile(getMimeType(illust, index), displayName);
        }
    }

    public static DocumentFile getGifDocument(Context context, IllustsBean illust) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        if (!root.exists() || !root.isDirectory()) {
            Common.showToast("创建文件失败，已授权的下载目录是否被移除?", true);
            return null;
        }
        String id = DocumentsContract.getTreeDocumentId(rootUri);
        String displayName = FileCreator.createGifFile(illust).getName();
        id = id + "/" + displayName;
        Uri childrenUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, id);
        DocumentFile childFile = DocumentFile.fromSingleUri(context, childrenUri);

        if (childFile != null && childFile.exists()) {
            return childFile;
        } else {
            assert root != null;
            return root.createFile("image/gif", displayName);
        }
    }

    public static DocumentFile findNovelFile(Context context, String displayName) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        return root.findFile(displayName);
    }

    public static File getImageCache(Context context) {
        File cacheDir = new File(context.getCacheDir().getPath() + "/image_manager_disk_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }
        return cacheDir;
    }

    public static File getGifCache(Context context) {
        File cacheDir = new File(context.getCacheDir().getPath() + "/gif cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }
        return cacheDir;
    }

    public static File getGifResultCache(Context context) {
        File cacheDir = new File(context.getCacheDir().getPath() + "/gif result cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }
        return cacheDir;
    }

    public static File createZipFile(Context context, String zipName) {
        File zipFile = new File(getGifCache(context), zipName);
        if (!zipFile.exists()) {
            try {
                zipFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return zipFile;
    }

    public static File createCacheUnzipFolder(Context context, IllustsBean illust) {
        String unzipDirName = FileCreator.createGifUnZipFolder(illust).getName();
        File unzipDirFile = new File(getGifCache(context).getPath() + "/" + unzipDirName);
        if (!unzipDirFile.exists()) {
            unzipDirFile.mkdir();
        }
        return unzipDirFile;
    }

    public static File createZipResultFile(Context context, String gifName) {
        File zipFile = new File(getGifResultCache(context), gifName);
        if (!zipFile.exists()) {
            try {
                zipFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return zipFile;
    }

    public static DocumentFile createNovelFile(Context context, String displayName) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        assert root != null;
        DocumentFile file = root.findFile(displayName);
        if (file != null) {
            return file;
        } else {
            return root.createFile("text/plain", displayName);
        }
    }

    public static String getMimeType(IllustsBean illust, int index) {
        String mimeType = "image/png";
        if (illust == null) {
            return mimeType;
        }

        String url;
        if (illust.getPage_count() == 1) {
            url = illust.getMeta_single_page().getOriginal_image_url();
        } else {
            url = illust.getMeta_pages().get(index).getImage_urls().getOriginal();
        }

        if (url.contains(".")) {
            mimeType = "image/" + url.substring(url.lastIndexOf(".") + 1);
        }
        Common.showLog("SAFile getMimeType: " + url + ", fileType: " + mimeType);
        return mimeType;
    }
}
