package ceui.lisa.file;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import ceui.lisa.activities.Shaft;
import ceui.lisa.download.FileCreator;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class SAFile {

    public static DocumentFile getDocument(Context context, IllustsBean illust, int index) {
        DocumentFile root = rootFolder(context);
        String displayName = FileCreator.customFileName(illust, index);
        String id = DocumentsContract.getTreeDocumentId(root.getUri());
        String subDirectoryName = getShaftDir(illust);
        id = id + "/" + subDirectoryName + "/" + displayName;
        Uri childrenUri = DocumentsContract.buildDocumentUriUsingTree(root.getUri(), id);
        DocumentFile realFile = DocumentFile.fromSingleUri(context, childrenUri);
        if (realFile != null && realFile.exists()) {
            try {
                DocumentsContract.deleteDocument(context.getContentResolver(), realFile.getUri());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        DocumentFile subDirectory = root.findFile(subDirectoryName);
        if(subDirectory == null){
            subDirectory = root.createDirectory(subDirectoryName);
        }
        return subDirectory.createFile(getMimeTypeFromIllust(illust, index), displayName);
    }

    public static DocumentFile rootFolder(Context context) {
        String rootUriString = Shaft.sSettings.getRootPathUri();
        Uri uri = Uri.parse(rootUriString);
        try {
            return DocumentFile.fromTreeUri(context, uri);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean isFileExists(Context context, IllustsBean illust) {
        return isFileExists(context, illust, 0);
    }

    public static boolean isFileExists(Context context, IllustsBean illust, int index) {
        DocumentFile root = rootFolder(context);
        if (root != null) {
            String id = DocumentsContract.getTreeDocumentId(root.getUri());
            String displayName = FileCreator.customFileName(illust, index);
            id = id + "/" + getShaftDir(illust) + "/" + displayName;
            Uri childrenUri = DocumentsContract.buildDocumentUriUsingTree(root.getUri(), id);
            DocumentFile realFile = DocumentFile.fromSingleUri(context, childrenUri);
            return realFile != null && realFile.exists();
        } else {
            return false;
        }
    }

    public static String getMimeTypeFromIllust(IllustsBean illust, int index) {
        String url;
        if (illust.getPage_count() == 1) {
            url = illust.getMeta_single_page().getOriginal_image_url();
        } else {
            url = illust.getMeta_pages().get(index).getImage_urls().getOriginal();
        }

        String result = "png";
        if (url.contains(".")) {
            result = url.substring(url.lastIndexOf(".") + 1);
        }
        Common.showLog("getMimeTypeFromIllust fileUrl: " + url + ", fileType: " + result);
        return "image/" + result;
    }

    private static String getShaftDir(IllustsBean illust) {
        return isSaveToR18Dir(illust) ? "ShaftImages-R18" : "ShaftImages";
    }

    private static boolean isSaveToR18Dir(IllustsBean illust){
        return illust.isR18File() && Shaft.sSettings.isR18DivideSave();
    }
}
