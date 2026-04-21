package ceui.lisa.download;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.model.CustomFileNameCell;
import ceui.lisa.models.IllustsBean;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.config.DownloadItems;

/**
 * Legacy entry point for illust / ugoira filename generation.
 *
 * All production paths delegate to {@link DownloadsRegistry#getDownloads()} —
 * the new download subsystem owns template rendering, sanitization and
 * path resolution.
 *
 * The cell-based methods ({@link #defaultFileCells()},
 * {@link #customFileNameForPreview(IllustsBean, List, int)}) remain for the
 * legacy settings UI ({@code FragmentFileName}) until it is rewritten against
 * the new template editor.
 */
public class FileCreator {

    public static final int ILLUST_TITLE = 1;
    public static final int ILLUST_ID = 2;
    public static final int P_SIZE = 3;
    public static final int USER_ID = 4;
    public static final int USER_NAME = 5;
    public static final int ILLUST_SIZE = 6;
    public static final int CREATE_TIME = 7;

    /**
     * Legacy filename-sanitization hook. Kept for callers that still hand-build
     * novel / export filenames; new code should go through the download facade,
     * which sanitizes automatically.
     *
     * Delegates to the single project-wide sanitizer so there is still only one
     * rule in effect.
     */
    public static String deleteSpecialWords(String before) {
        if (before == null || before.isEmpty()) {
            return "untitle_" + System.currentTimeMillis() + ".png";
        }
        return ceui.pixiv.download.sanitize.FsSanitizer.INSTANCE.cleanSegment(before, true);
    }

    public static boolean isExist(IllustsBean illust, int index) {
        try {
            var item = illust.isGif()
                    ? DownloadItems.ugoira(illust)
                    : DownloadItems.illustPage(illust, index);
            var plan = DownloadsRegistry.getDownloads().plan(item);
            return plan.getBackend().exists(plan.getPath());
        } catch (Throwable t) {
            return false;
        }
    }

    public static String customFileName(IllustsBean illustsBean, int index) {
        var plan = DownloadsRegistry.getDownloads().plan(
                DownloadItems.illustPage(illustsBean, index));
        return plan.getPath().getFilename();
    }

    public static String customGifFileName(IllustsBean illustsBean) {
        var plan = DownloadsRegistry.getDownloads().plan(
                DownloadItems.ugoira(illustsBean));
        return plan.getPath().getFilename();
    }

    /**
     * Legacy settings-UI preview — still uses the old cell template so users
     * editing in {@code FragmentFileName} see what they are configuring. Not
     * used for any actual download.
     */
    public static String customFileNameForPreview(IllustsBean illustsBean,
                                                  List<CustomFileNameCell> cells, int index) {
        // Preview intentionally mirrors the new system's output so UI shows
        // what will actually be saved — cell list is ignored.
        return customFileName(illustsBean, index);
    }

    /**
     * Legacy settings-UI default template — still exported for the old cell
     * editor until it is retired. The returned list does not influence real
     * downloads.
     */
    public static List<CustomFileNameCell> defaultFileCells() {
        List<CustomFileNameCell> cells = new ArrayList<>();
        cells.add(new CustomFileNameCell("作品标题", "作品标题，可选项", 1, true));
        cells.add(new CustomFileNameCell("作品ID", "不选的话可能两个文件名重复，导致下载失败，必选项", 2, true));
        cells.add(new CustomFileNameCell("作品P数", "显示当前图片是作品的第几P，必选项", 3, true));
        cells.add(new CustomFileNameCell("画师ID", "画师ID，可选项", 4, false));
        cells.add(new CustomFileNameCell("画师昵称", "画师昵称，可选项", 5, false));
        cells.add(new CustomFileNameCell("作品尺寸", "显示当前图片的尺寸信息，可选项", 6, false));
        cells.add(new CustomFileNameCell("创作时间", "创作时间，可选项", 7, false));
        return cells;
    }
}
