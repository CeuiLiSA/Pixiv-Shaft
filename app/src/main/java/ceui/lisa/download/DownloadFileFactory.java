package ceui.lisa.download;

import android.net.Uri;

/**
 * 下载文件工厂，替代 rxhttp.wrapper.callback.UriFactory。
 * 负责查询已有文件和创建新文件。
 */
public interface DownloadFileFactory {

    /** 查询已存在的文件 Uri，不存在返回 null */
    Uri query();

    /** 创建/准备目标文件，返回可写入的 Uri */
    Uri insert();

    /** 获取最终文件 Uri（下载完成后用） */
    Uri getFileUri();
}
