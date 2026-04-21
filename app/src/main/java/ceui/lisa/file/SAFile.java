package ceui.lisa.file;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import ceui.lisa.activities.Shaft;
import ceui.lisa.models.IllustsBean;
import ceui.pixiv.download.Downloads;
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
            Downloads.Plan plan = DownloadsRegistry.getDownloads().plan(item);
            return plan.getBackend().exists(plan.getPath());
        } catch (Throwable t) {
            return false;
        }
    }
}
