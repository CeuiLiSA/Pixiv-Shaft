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

    /**
     * 下载流写完并 close 之后调用一次。
     * 用于让目标 Uri 对系统相册可见：
     *   - MediaStore (Q+)：清除 IS_PENDING 标志，触发观察者刷新
     *   - 旧版本 / SAF：调用 MediaScannerConnection.scanFile
     * 默认空实现，方便实现类按需覆盖。
     */
    default void finishWrite() {}

    /**
     * 下载因网络失败 / 取消 / 异常等原因半途而废时调用，取消 insert() 阶段
     * 留下的痕迹（MediaStore 行 / SAF 文件 / 缓存文件）。
     *
     * 不调用本方法的后果：MediaStore 上 IS_PENDING=1 的行不会被清除，
     * 用户在文件管理器里看到一堆 0 字节的 `.pending-` 临时文件（issue #857）。
     *
     * 与 finishWrite() 二选一调用：成功 finishWrite，失败 abandonWrite。
     * 默认空实现，方便实现类按需覆盖。
     */
    default void abandonWrite() {}
}
