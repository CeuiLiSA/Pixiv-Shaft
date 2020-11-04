package ceui.lisa.core;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;

import ceui.lisa.activities.Shaft;
import ceui.lisa.download.FileCreator;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class SAFile {

    public static DocumentFile getDocument(Context context, IllustsBean illust, int index) {
        Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        String displayName = FileCreator.createIllustFile(illust, index).getName();
        assert root != null;
        DocumentFile file = root.findFile(displayName);
        if (file != null) {
            return file;
        } else {
            return root.createFile(getMimeType(illust, index), displayName);
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
