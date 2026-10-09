package ceui.lisa.core;

import android.text.TextUtils;

import java.io.Serializable;
import java.util.UUID;

import ceui.lisa.download.FileCreator;
import ceui.lisa.file.FileName;
import ceui.pixiv.api.model.Illust;
import ceui.lisa.utils.Common;

public class DownloadItem implements Serializable {

    private String name;
    private String url;
    private String showUrl;
    private String uuid;
    private final Illust illust;
    private int index;
    private boolean autoSave = true;
    // 这三个字段被多个线程读写、且没有任何同步块护着，必须 volatile：
    //   - state：主线程的 pumpAvailableSlots / startAll / stopAll、IO worker 的
    //     complete()、批量队列协程的 retry path 都在写；判定方（pump 挑页、队列收口）
    //     读不到最新值就会"翻了 INIT 却挑不到"，或者"页已成功却仍算未完成"。
    //   - paused：同上，跨线程读点在 getFirstReady / activeCount / 队列的 footprint。
    //   - nonius：进度百分比（显示 / 日志用）。写方横跨主线程（reportProgress 的
    //     postMain 体）和 IO worker（complete 失败时归零），读方在别的线程看日志。
    private volatile int state = DownloadState.INIT;
    private volatile boolean paused = false;
    // 批量队列产生的 page 不逐条弹完成/失败 Toast，由队列收口时统一弹汇总（issue #950）。
    // 参与序列化：冷启动 Manager.restore 带回的批量 page 也要保持静默。
    // 只在构造时写、之后只读，靠 content.add 的 happens-before 传递即可，无需 volatile。
    private boolean silent = false;
    private volatile int nonius = 0;
    // currentSize / totalSize 保持普通字段：写方是主线程的进度回调（reportProgress 的
    // postMain 体），读方是主线程的 UI。唯一的跨线程读点是批量队列的进展评分，而它优先
    // 读 stage 文件（.part）的真实长度 —— 队列里的页目标都是 content://、一律走 staging，
    // 所以"退回 currentSize"那个兜底分支实际不会命中。不为一个不命中的分支付 volatile。
    private transient long currentSize = 0;
    private transient long totalSize = 0;
    /**
     * 最近一次**真正读到字节**的时刻（{@code SystemClock.elapsedRealtime()} 毫秒）。
     *
     * 由 {@code Manager.pumpBytes} 在 **IO 线程**每次成功 read 后写；UI 端读它算「已断流 N 秒」。
     * 断流计时的起点必须是这个**源头时刻**，而不是 UI 观察到 currentSize 变化的时刻 —— 后者要等
     * 主线程处理完 progress 回调（且上报本身有 500ms 节流）才看得到，主线程一被进度流刷满，起点
     * 就整体后飘，表现为「UI 才 7s、OkHttp 读超时已经 10s」。
     *
     * transient：不参与 Gson / Serializable 持久化；volatile：IO 线程写、主线程读。
     * 0 = 本次传输还没读到首字节（建连 / 等响应头阶段，不算断流）。
     */
    private transient volatile long lastByteAtMs = 0L;

    /**
     * 「读超时静默重连」这条 item 是否已经用过（见 {@code Manager} 的读超时重连判定）。
     *
     * 每条只给一次：对端真挂了的时候，无限重连只会让队列永远转下去，用户还看不出问题。
     *
     * transient：不落盘。冷启动恢复出来的 item 一律 INIT、本来就要重新下一遍；用户手动重试
     * （FAILED→INIT，见 Manager.resurrectIfStranded）时也会清零，那次重试重新拿一次额度。
     */
    private transient volatile boolean readTimeoutRetryUsed = false;

    public DownloadItem(Illust illustsBean, int index) {
        this.illust = illustsBean;
        this.uuid = UUID.randomUUID().toString();
        if (this.illust.isGif()) {
            this.name = new FileName().zipName(illustsBean);
        } else {
            this.name = FileCreator.customFileName(illustsBean, index);
        }
        this.index = index;
        Common.showLog("随机生成一个UUID");
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getShowUrl() {
        return showUrl;
    }

    public void setShowUrl(String showUrl) {
        this.showUrl = showUrl;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public Illust getIllust() {
        return illust;
    }

    public void setUrl(String url) {
        Common.showLog("DownloadItem 准备下载：" + url);
        this.url = url;
        if (!illust.isGif()) {
            // 非原图可能是 JPEG，下载记录和 EXIF 判定也必须使用实际格式。
            this.name = FileCreator.customFileName(illust, index, url);
        }
    }

    public boolean isAutoSave() {
        return autoSave;
    }

    public void setAutoSave(boolean autoSave) {
        this.autoSave = autoSave;
    }

    public boolean isSilent() {
        return silent;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
    }

    public boolean isSame(DownloadItem next) {
        return next != null &&
                TextUtils.equals(name, next.name) &&
                TextUtils.equals(url, next.url);
    }

    public boolean isFailed() {
        return this.state == DownloadState.FAILED;
    }

    public int getState() {
        if (this.state == DownloadState.FAILED) {
            return DownloadState.FAILED;
        }
        if (this.paused) {
            return DownloadState.PAUSED;
        }
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused(){
        return this.paused;
    }

    public int getNonius() {
        return nonius;
    }

    public void setNonius(int nonius) {
        this.nonius = nonius;
    }

    public long getCurrentSize() {
        return currentSize;
    }

    public void setCurrentSize(long currentSize) {
        this.currentSize = currentSize;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    /** 见 {@link #lastByteAtMs}。0 = 本次传输还没读到首字节。 */
    public long getLastByteAtMs() {
        return lastByteAtMs;
    }

    /** 由 {@code Manager.pumpBytes} 在 IO 线程调用；不要在 UI 侧写。 */
    public void setLastByteAtMs(long lastByteAtMs) {
        this.lastByteAtMs = lastByteAtMs;
    }

    /** 见 {@link #readTimeoutRetryUsed}。 */
    public boolean isReadTimeoutRetryUsed() {
        return readTimeoutRetryUsed;
    }

    /** 见 {@link #readTimeoutRetryUsed}。 */
    public void setReadTimeoutRetryUsed(boolean readTimeoutRetryUsed) {
        this.readTimeoutRetryUsed = readTimeoutRetryUsed;
    }

    public boolean shouldStartNewDownload() {
        return this.state == DownloadState.INIT || this.state == DownloadState.FAILED;
    }

    public static class DownloadState {
        public static final int INIT = 0;
        public static final int DOWNLOADING = 1;
        public static final int SUCCESS = 2;
        public static final int FAILED = 3;
        public static final int PAUSED = 4;
    }
}
