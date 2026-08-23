package ceui.lisa.file;

import android.content.Context;

import ceui.loxia.Illust;
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

    public static boolean isFileExists(Context context, Illust illust) {
        return isFileExists(context, illust, 0);
    }

    public static boolean isFileExists(Context context, Illust illust, int index) {
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
