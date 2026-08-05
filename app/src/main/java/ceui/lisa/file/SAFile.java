package ceui.lisa.file;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import ceui.lisa.activities.Shaft;
import ceui.lisa.models.IllustsBean;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.config.DownloadItems;

/**
 * Legacy SAF helper. The only remaining responsibility is existence checking
 * for illust pages; everything else now lives inside the download facade.
 *
 * Kept as a thin shim so existing call sites ({@code Common.isFileExists})
 * compile without edits.
 */
public class SAFile {

    public static boolean isFileExists(Context context, IllustsBean illust) {
        return isFileExists(context, illust, 0);
    }

    /**
     * Legacy accessor — still used to probe whether the user's saved SAF tree
     * is reachable before kicking off a download. Kept as a thin helper;
     * production paths now own root validation inside the facade.
     */
    public static DocumentFile rootFolder(Context context) {
        String uri = Shaft.sSettings != null ? Shaft.sSettings.getRootPathUri() : null;
        if (TextUtils.isEmpty(uri)) return null;
        try {
            return DocumentFile.fromTreeUri(context, Uri.parse(uri));
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isFileExists(Context context, IllustsBean illust, int index) {
        try {
            ceui.pixiv.download.model.DownloadItem item = illust.isGif()
                    ? DownloadItems.ugoira(illust)
                    : DownloadItems.illustPage(illust, index);
            // existsAt 是纯存在性探测；不要走 plan() —— 它的 Skip 分支在 SAF 下
            // 会用 createFile 碰撞探测，这里只是查询，不该产生文件副作用。
            return DownloadsRegistry.getDownloads().existsAt(item);
        } catch (Throwable t) {
            return false;
        }
    }
}
