package ceui.lisa.core;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import ceui.lisa.activities.Shaft;
import ceui.lisa.download.FileCreator;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class SAFile {

    public static DocumentFile getDocument(Context context, IllustsBean illust, int index) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
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

    public static DocumentFile findGifFile(Context context, IllustsBean illust) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        String displayName = FileCreator.createGifZipFile(illust).getName();
        return root.findFile(displayName);
    }

    public static DocumentFile findNovelFile(Context context, String displayName) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        return root.findFile(displayName);
    }

    public static DocumentFile findGifUnzipFolder(Context context, IllustsBean illust) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        String displayName = FileCreator.createGifUnZipFolder(illust).getName();
        DocumentFile file = root.findFile(displayName);
        if (file != null && file.isDirectory()) {
            return file;
        } else {
            return root.createDirectory(displayName);
        }
    }

    public static DocumentFile createGifFile(Context context, IllustsBean illust) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        String displayName = FileCreator.createGifZipFile(illust).getName();
        assert root != null;
        DocumentFile file = root.findFile(displayName);
        if (file != null) {
            return file;
        } else {
            return root.createFile("application/zip", displayName);
        }
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
