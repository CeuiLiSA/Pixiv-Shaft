package ceui.lisa.download;

/**
 * 下载进度，替代 rxhttp.wrapper.entity.Progress
 */
public class DownloadProgress {

    private int progress;
    private long currentSize;
    private long totalSize;

    public DownloadProgress(int progress, long currentSize, long totalSize) {
        this.progress = progress;
        this.currentSize = currentSize;
        this.totalSize = totalSize;
    }

    public int getProgress() {
        return progress;
    }

    public long getCurrentSize() {
        return currentSize;
    }

    public long getTotalSize() {
        return totalSize;
    }
}
