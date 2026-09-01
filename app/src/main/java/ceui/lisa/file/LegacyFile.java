package ceui.lisa.file;

import android.content.Context;

import java.io.File;

import ceui.pixiv.api.model.Illust;
import ceui.lisa.utils.Common;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

public class LegacyFile {

    private static final String GIF_CACHE = "/gif cache";
    private static final String IMAGE_CACHE = "/image_manager_disk_cache";
    private static final String REVERSE_SEARCH_CACHE = "/reverse_search";

    public static File imageCacheFolder(Context context) {
        File cacheDir = new File(context.getCacheDir().getPath() + IMAGE_CACHE);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        Common.showLog("LegacyFile imageCacheFolder " + cacheDir.getPath());
        return cacheDir;
    }

    /**
     * 图搜待上传图片的落点。单独一个目录、不蹭 Glide 的 image_manager_disk_cache ——
     * 这个目录要在 provider_paths.xml 里对 FileProvider 开放，开放面越小越好。
     */
    public static File reverseSearchCacheFolder(Context context) {
        File cacheDir = new File(context.getCacheDir().getPath() + REVERSE_SEARCH_CACHE);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir;
    }

    public static File gifCacheFolder(Context context) {
        // getExternalCacheDir() 在外部存储不可用(卷被卸载 / 模拟外存缺失)时返回 null,
        // 直接 .getPath() 会 NPE 把整个 Settings 页炸掉。退回内部 cache 目录——
        // 这也是 GIF 缓存最初的落点(见 21c86322 之前),外存不可用时本就该走内部存储。
        File base = context.getExternalCacheDir();
        if (base == null) {
            base = context.getCacheDir();
        }
        File cacheDir = new File(base.getPath() + GIF_CACHE);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        Common.showLog("LegacyFile gifCacheFolder " + cacheDir.getPath());
        return cacheDir;
    }

    public static File gifZipFile(Context context, Illust illust) {
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

    public static File gifUnzipFolder(Context context, Illust illust) {
        String folderName = new FileName().unzipName(illust);
        File unzipDirFile = new File(gifCacheFolder(context).getPath() + "/" + folderName);
        if (!unzipDirFile.exists()) {
            unzipDirFile.mkdirs();
        }
        Common.showLog("LegacyFile gifUnzipFolder " + unzipDirFile.getPath());
        return unzipDirFile;
    }

    public static File gifResultFile(Context context, Illust illust) {
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

    public static File textFile(Context context, String name) {
        File gifCacheFolder = gifCacheFolder(context);
        File textFile = createValidFile(gifCacheFolder, name);
        if (!textFile.exists()) {
            try {
                textFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return textFile;
    }

    private static final int MAX_FILENAME_BYTES = 255;
    private static final int MAX_PATH_BYTES = 4096;

    public static File createValidFile(File parentDir, String fileName) {
        String safeName = safeFileName(fileName);
        File tempFile = new File(parentDir, safeName);
        int fileLength = tempFile.getAbsolutePath().getBytes().length;
        if (fileLength > MAX_PATH_BYTES) Common.showLog("LegacyFile createValidFile fileLength > MAX_PATH_BYTES ?");

        return tempFile;
    }

    public static String safeFileName(String fileName) {
        if (fileName == null)return "_";
        String filtered =fileName.replaceAll("[\\p{C}\\\\<>/:\"|?*]", "_");
        if (filtered.isEmpty()) {
            return "_";
        }
        return truncateToBytes(filtered, MAX_FILENAME_BYTES-3);
    }

    private static String truncateToBytes(final String input, final int maxBytes) {
        if (input == null || maxBytes <= 0 || input.isEmpty()) return "";

        byte[] sba = input.getBytes(StandardCharsets.UTF_8);
        if (sba.length <= maxBytes) return input;

        CharsetDecoder cd = StandardCharsets.UTF_8.newDecoder();
        cd.onMalformedInput(CodingErrorAction.IGNORE);
        cd.onUnmappableCharacter(CodingErrorAction.IGNORE);

        int lastDotIndex = input.lastIndexOf('.');
        String extension = (lastDotIndex == -1) ? "" : input.substring(lastDotIndex);

        byte[] extBytes = extension.getBytes(StandardCharsets.UTF_8);
        int extLength = extBytes.length;

        ByteBuffer bb = ByteBuffer.wrap(sba, 0, maxBytes-extLength);
        CharBuffer cb = CharBuffer.allocate(maxBytes);
        cd.decode(bb, cb, false);
        cd.decode(ByteBuffer.wrap(extBytes), cb, true);
        cd.flush(cb);

        return  new String(cb.array(), 0, cb.position());
    }

}
