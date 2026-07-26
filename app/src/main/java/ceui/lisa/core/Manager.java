package ceui.lisa.core;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadEntity;
import ceui.lisa.database.DownloadingEntity;
import ceui.lisa.download.DownloadFileFactory;
import ceui.lisa.download.DownloadProgress;
import ceui.lisa.download.MediaStoreUtil;
import ceui.lisa.helper.Android10DownloadFactory22;
import ceui.lisa.helper.SAFactory;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.model.Holder;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.DownloadLimitTypeUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.download.RecordedPageProbe;
import ceui.pixiv.download.aria2.Aria2Dispatcher;
import ceui.pixiv.imageloader.ImageLoaderV3;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Manager {
    Map<String, Call> runningCalls = new ConcurrentHashMap<>();
    private final Context mContext = Shaft.getContext();
    private List<DownloadItem> content = new ArrayList<>();
    /**
     * 多任务并发下载的运行 disposable 表（key = item.uuid）。
     * 旧设计是单一 `Disposable handle`（串行），现在支持 1-5 并发：每个正在传的
     * page 各占一个 entry。stopOne(uuid) 只 dispose 那一个；stopAll() dispose 全部。
     */
    private final Map<String, Disposable> handles = new ConcurrentHashMap<>();
    private boolean isRunning = false;

    /**
     * 下载专用 OkHttpClient —— **强制 HTTP/1.1**。
     *
     * 全局 {@code Shaft.getOkHttpClient()} 在非直连模式下默认 [H2, H1.1]，pixiv CDN
     * 选 H2 后所有并发下载流复用一条 TCP 连接 + H2 多路复用，**服务器端 stream
     * priority 严格串行**：stream 1 的 body 推完才发 stream 2/3 的 response headers，
     * `client.newCall(req).execute()` 在 2/3 上分别卡到 600ms+ / 几秒（实测 4.4s），
     * 用户视角"3 个 item 都标 DOWNLOADING 但只有第一个进度在跳"。
     *
     * 改用 H1.1：每个 sync 请求拿独立 TCP 连接（OkHttp ConnectionPool 默认
     * maxIdleConnections=5 够 5 并发用），各自独立 flow control，真正并行。
     *
     * `newBuilder()` 继承全局 client 的 DNS / SSL / interceptor / ProgressManager
     * 配置，只覆盖 protocols。直连模式下全局已经是 H1.1，这里再强制一次也无害（幂等）。
     */
    private volatile OkHttpClient mDownloadOkHttpClient;
    private OkHttpClient getDownloadOkHttpClient() {
        OkHttpClient cached = mDownloadOkHttpClient;
        if (cached != null) return cached;
        synchronized (this) {
            if (mDownloadOkHttpClient == null) {
                OkHttpClient base = ((Shaft) Shaft.getContext()).getOkHttpClient();
                mDownloadOkHttpClient = base.newBuilder()
                        .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                        .build();
            }
            return mDownloadOkHttpClient;
        }
    }

    private Manager() {
        uuid = "";
        currentIllustID = 0;
    }

    /**
     * 恢复未完成的下载任务。每条记录的 taskGson 内嵌完整 IllustsBean (~80KB)，
     * 过去全表加载在主线程触发过 OOM (CursorWindow.nativeGetString)。改为：
     *   1) 后台线程执行，避免阻塞 UI 启动；
     *   2) 限制条数，并先 trim 历史堆积条目；
     *   3) 包一层 try/catch，OOM/DB 异常时静默跳过而不让启动崩溃。
     */
    private static final int MAX_RESTORE_ITEMS = 100;

    // 字节级限速已彻底删除（debug 调试可视化的 500KB/s 节流）。
    // 唯一保留的是 BulkObjectFetcher.RATE_LIMIT_MS（页间 API 间隔，防 pixiv 429）。

    public void restore() {
        Schedulers.io().scheduleDirect(() -> {
            try {
                AppDatabase db = AppDatabase.getAppDatabase(mContext);
                db.downloadDao().trimDownloading(MAX_RESTORE_ITEMS);
                List<DownloadingEntity> downloadingEntities =
                        db.downloadDao().getRecentDownloading(MAX_RESTORE_ITEMS);
                if (Common.isEmpty(downloadingEntities)) {
                    return;
                }
                Common.showLog("downloadingEntities " + downloadingEntities.size());
                List<DownloadItem> restored = new ArrayList<>();
                for (DownloadingEntity entity : downloadingEntities) {
                    try {
                        DownloadItem downloadItem = Shaft.sGson.fromJson(entity.getTaskGson(), DownloadItem.class);
                        if (downloadItem != null) {
                            restored.add(downloadItem);
                        }
                    } catch (Exception ex) {
                        Common.showLog("Manager restore parse error: " + ex.getMessage());
                    }
                }
                synchronized (this) {
                    content = restored;
                }
                ManagerReactive.invalidate();
                AndroidSchedulers.mainThread().scheduleDirect(() ->
                        Common.showToast("下载记录恢复成功"));
            } catch (Throwable t) {
                Common.showLog("Manager restore failed: " + t.getMessage());
            }
        });
    }

    public static Manager get() {
        return Manager.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final Manager INSTANCE = new Manager();
    }

    public void addTask(DownloadItem bean) {
        // 纵深防御:null item 会在 safeAdd 里 null.getUuid() NPE(见 gif 走 buildDownloadItem
        // 返 null 的历史坑)。调用方本应先过滤,这里再兜一层,任何 null 直接忽略不崩。
        if (bean == null) {
            return;
        }
        synchronized (this) {
            if (content == null) {
                content = new ArrayList<>();
            }

            boolean isTaskExist = false;
            for (DownloadItem item : content) {
                if (item.isSame(bean)) {
                    isTaskExist = true;
                }
            }

            if (!isTaskExist) {
                // content.add 必须同步(safeAdd 内)：triggerPump 的 getFirstReady 和
                // QueueDownloadManager.fillSlots 的 footprint 都靠 content 立刻变长。
                // 但 addTask 跑在主线程(详情页下载按钮),DownloadingEntity 的
                // Gson(~80KB)+Room insert 若同步执行,会卡在被并发
                // hasDownloadRecordByIllustId LIKE 全表扫描占满的 SQLite 连接池上 →
                // 主线程 ANR。故 persistAsync=true：add 同步、持久化挪后台。
                // (批量版 addTasks 早已整段在 Schedulers.io(),这里对齐它的教训。)
                safeAdd(bean, true);
            }
            if(DownloadLimitTypeUtil.startTaskWhenCreate()){
                // 见 triggerPump 的 javadoc —— addTask 是连续调用 hot path
                // （fillSlots 一轮要 addTask N 次），绝不能走 startAll。
                triggerPump();
            }
        }
        ManagerReactive.invalidate();
    }

    /**
     * 给 [ManagerReactive.contentFlow] 用：返回 [content] 当前的浅拷贝快照。
     * synchronized 跟 addTask / safeAdd / clearAll 等写入路径互斥，避免拷贝
     * 时撞上 ConcurrentModificationException。
     */
    public List<DownloadItem> contentSnapshot() {
        synchronized (this) {
            return new ArrayList<>(content);
        }
    }

    private void safeAdd(DownloadItem item) {
        safeAdd(item, false);
    }

    /**
     * @param persistAsync true 时把 DownloadingEntity 的持久化(Gson 序列化 + Room
     *   insert)丢到 Schedulers.io() 后台执行，只保证 content.add 同步返回。
     *   addTask 从主线程调用必须传 true —— 同步写 Room 会卡在被并发
     *   hasDownloadRecordByIllustId LIKE 全表扫描占满的 SQLite 连接池上 → 主线程 ANR。
     *   addTasks 整段已在 IO 线程,传 false 同步持久化即可(避免每条 fan-out 一个 IO 任务)。
     */
    private void safeAdd(DownloadItem item, boolean persistAsync) {
        Common.showLog("Manager safeAdd " + item.getUuid());
        content.add(item);
        if (persistAsync) {
            final DownloadItem toPersist = item;
            Schedulers.io().scheduleDirect(() -> {
                try {
                    persistDownloading(toPersist);
                } catch (Throwable t) {
                    Common.showLog("safeAdd persistDownloading failed: " + t.getMessage());
                }
            });
        } else {
            persistDownloading(item);
        }
    }

    /**
     * 写 illust_downloading_table 的崩溃恢复记录 —— 只有 restore() 冷启动时读回,
     * 用来续未完成的下载。Gson(~80KB) + Room insert 都是重 IO,绝不能在主线程跑。
     */
    private void persistDownloading(DownloadItem item) {
        DownloadingEntity entity = new DownloadingEntity();
        entity.setFileName(item.getName());
        entity.setUuid(item.getUuid());
        entity.setTaskGson(Shaft.sGson.toJson(item));
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertDownloading(entity);
    }

    /**
     * 标记任务完成。可在任意线程调用。
     * 注意：content 列表修改不在此方法中，由调用方在主线程统一处理。
     */
    private void complete(DownloadItem item, boolean isDownloadSuccess) {
        if (isDownloadSuccess) {
            item.setState(DownloadItem.DownloadState.SUCCESS);
            setCallback(uuid, null);

            // Gson + DB 操作（IO 安全）
            DownloadingEntity entity = new DownloadingEntity();
            entity.setFileName(item.getName());
            entity.setUuid(item.getUuid());
            entity.setTaskGson(Shaft.sGson.toJson(item));
            AppDatabase.getAppDatabase(mContext).downloadDao().deleteDownloading(entity);
        } else {
            item.setNonius(0);
            item.setState(DownloadItem.DownloadState.FAILED);
        }
        ManagerReactive.invalidate();
    }

    public void addTasks(List<DownloadItem> list) {
        if (Common.isEmpty(list)) return;

        // Gson 序列化 + DB INSERT 是重操作（172P 场景需序列化 ~13MB JSON + 172 次 INSERT），
        // 必须在后台线程执行，否则主线程卡死。
        Schedulers.io().scheduleDirect(() -> {
            long t0 = System.nanoTime();
            synchronized (this) {
                if (content == null) {
                    content = new ArrayList<>();
                }
                // 批量构建一个 HashSet 做 O(1) 去重，避免 O(n^2) 逐项扫描
                java.util.Set<String> existingUrls = new java.util.HashSet<>();
                for (DownloadItem existing : content) {
                    existingUrls.add(existing.getUrl());
                }
                for (DownloadItem item : list) {
                    // 与 addTask 对齐:跳过 null item(gif 走 buildDownloadItem 返 null 的历史坑),
                    // 否则 item.getUrl() 直接 NPE。调用方本应先过滤,这里兜底。
                    if (item != null && !existingUrls.contains(item.getUrl())) {
                        safeAdd(item);
                        existingUrls.add(item.getUrl());
                    }
                }
            }
            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            Common.showLog("[PERF] addTasks total=" + list.size()
                    + " items, totalMs=" + totalMs);
            // batch 完一次 invalidate 即可（DROP_OLDEST 保证多次 tryEmit 自动合并，
            // 但仍是最佳实践：每个语义批次 emit 一次而不是每条 safeAdd 都 emit）
            ManagerReactive.invalidate();
            AndroidSchedulers.mainThread().scheduleDirect(() -> {
                if (DownloadLimitTypeUtil.startTaskWhenCreate()) {
                    triggerPump();
                }
            });
        });
    }

    public void startAll() {
        Common.showLog("[DL-RACE] startAll (用户态 resume / 冷启动残留路径，走 resurrect)");
        // 用 snapshot 迭代：item.setPaused/state 是各自字段写入,跟 content 结构无关;
        // 直接 for-each live content 跟并发的 addTasks/safeAdd/remove 抢 modCount,CME。
        for (DownloadItem item : contentSnapshot()) {
            item.setPaused(false);
            resurrectIfStranded(item);
        }
        isRunning = true;
        // 之前 isRunning=true 时会 short-circuit return，假设单线程串行用 doFinally
        // 自动驱动下一条；并发模式下需要每次都 pumpAvailableSlots() 来填满空闲槽位。
        pumpAvailableSlots();
        ManagerReactive.invalidate();
    }

    /**
     * 内部 dispatch trigger —— 等价于 {@code isRunning = true; pumpAvailableSlots();}，
     * 但 **不走 startAll 的 [resurrectIfStranded] 循环**。
     *
     * 用于"刚 addTask 一批 / fillSlots 末尾想 trigger pump"等场景：这时 content 里
     * 可能有刚 [pumpAvailableSlots] 抢占式 setState(DOWNLOADING) 但 [startDownloadChain]
     * 里 [handles].put(uuid) 还在 Schedulers.io → mainThread 异步排队中的 in-flight item。
     * 走 startAll 会让 resurrectIfStranded 看到"DOWNLOADING && !handles.containsKey"
     * 误判 stranded → setState(INIT) + setNonius(0) → pump 又把它当 INIT 挑出来再
     * dispatch 一次，导致：
     *   - 同 uuid 两条 Observable 抢同一个 stage 文件 / targetUri（实测出过两次 read-start）
     *   - UI 进度一会儿前进一会儿回到 0% 闪烁
     *
     * 用户态 "全部继续" 按钮 / 冷启动 restore 后的残留清理仍然走 [startAll]——
     * 那些场景 handles 是干净的（stopAll dispose 完或进程刚启动）不会撞到 race。
     */
    public synchronized void triggerPump() {
        Common.showLog("[DL-RACE] triggerPump (addTask / QueueDownloadManager.didAdd 路径，无 resurrect)");
        isRunning = true;
        pumpAvailableSlots();
    }

    public void startOne(String uuid) {
        for (DownloadItem downloadItem : contentSnapshot()) {
            if (downloadItem != null && downloadItem.getUuid().equals(uuid)) {
                downloadItem.setPaused(false);
                resurrectIfStranded(downloadItem);
                Common.showLog("已开始 " + uuid);
                break;
            }
        }

        isRunning = true;
        pumpAvailableSlots();
        ManagerReactive.invalidate();
    }

    /**
     * 把"看似在跑但实际已经没有 disposable 在背后撑着"的 item 翻回 INIT，让
     * [pumpAvailableSlots] 重新挑选派发。两种来源：
     *
     *  1. **stopAll() 之后**：stopAll 把 paused=true、dispose 全部 handles，但
     *     **不动 state 字段**——getState() 在 paused=true 时直接返回 PAUSED，
     *     UI 也显示 PAUSED，没问题。但 startAll 的 paused=false 一翻，state 字段
     *     原值（DOWNLOADING）就暴露出来；这时 handles 已经空了，没人真在传。
     *     pumpAvailableSlots 的 activeCount() 把它们算进活跃数，getFirstReady 又
     *     只挑 INIT —— 槽位永远拉不到，下载彻底卡死（issue #873）。
     *
     *  2. **冷启动 restore 带回的 stranded DOWNLOADING**：进程被杀时正在传的 item，
     *     restore 之后 state 字段是 DOWNLOADING，但原 Disposable 已随进程消失。
     *     QueueDownloadManager.kt:596 的 retry path 历史上自己处理过这种情况，
     *     现在统一收口到这里。
     *
     * FAILED 也一起翻 INIT，让 retry 自然走 pump 路径。
     * 正在跑的 page（handles 里有 uuid）绝不能动 —— 否则把 DOWNLOADING 翻 INIT 后
     * pump 会再 dispatch 一条 Observable，跟原 chain 抢同一个 stage 文件 / targetUri，
     * 实测出过同 uuid 两次 read-start。
     */
    private void resurrectIfStranded(DownloadItem item) {
        int s = item.getState();
        if (s == DownloadItem.DownloadState.FAILED) {
            // FAILED→INIT 是预期 retry 路径，常态会触发；DEBUG 级别看就行
            Common.showLog("[DL-RACE] resurrect FAILED→INIT uuid=" + item.getUuid()
                    + " nonius=" + item.getNonius());
            item.setState(DownloadItem.DownloadState.INIT);
            return;
        }
        if (s == DownloadItem.DownloadState.DOWNLOADING
                && !handles.containsKey(item.getUuid())) {
            // 只翻状态。**不再** setNonius(0) / setCurrentSize(0) —— 来这里的两种场景：
            //   - 用户 pause → resume：stage 文件仍在 cacheDir，下次 downloadOne 用
            //     stageFile.length() 做 Range 头续传，第一个 chunk 进度直接回到原值
            //     （比如 47%）。归零会让 UI 看到一瞬 "47% → 0% → 47%" 回弹（state
            //     翻 INIT 时 progress bar GONE 不可见，但 pumpAvailableSlots 立刻翻回
            //     DOWNLOADING，那一帧暴露的就是被归零的脏值，第一个 chunk callback
            //     才会更新成真实续传位置）。
            //   - 冷启动 restore 后 stranded：同理，stage 文件跨进程仍在。
            // 481b06e0 当初归零是想"UI 不留进度残影"，但 ActiveListV3Fragment 在 INIT
            // 状态 progress.visibility = GONE，根本不会显示残影；归零反而把脏值
            // 暴露在 re-dispatch 后那一帧。
            Common.showLog("[DL-RACE] resurrect DOWNLOADING→INIT uuid=" + item.getUuid()
                    + " nonius=" + item.getNonius() + " currentSize=" + item.getCurrentSize()
                    + " (preserving progress for stage-resume)");
            item.setState(DownloadItem.DownloadState.INIT);
        }
    }

    public void stopAll() {
        for (DownloadItem item : contentSnapshot()) {
            item.setPaused(true);
        }
        isRunning = false;
        // dispose 全部正在传输的下载（snapshot 防 CME）
        for (Disposable d : new ArrayList<>(handles.values())) {
            try { d.dispose(); } catch (Exception ignored) {}
        }
        handles.clear();
        Common.showLog("已经停止");
        ManagerReactive.invalidate();
    }

    public void stopOne(String uuid){
        for (DownloadItem item : contentSnapshot()) {
            if(item.getUuid().equals(uuid)){
                item.setPaused(true);
                Call call = runningCalls.remove(uuid);
                if (call != null) {
                    call.cancel();
                }
                Common.showLog("已暂停 " + uuid);
                break;
            }
        }
        Disposable d = handles.remove(uuid);
        if (d != null) {
            try { d.dispose(); } catch (Exception ignored) {}
        }
        ManagerReactive.invalidate();
    }

    public void clearAll() {
        stopAll();
        AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllDownloading();
        synchronized (this) {
            content.clear();
        }
        ManagerReactive.invalidate();
    }

    public void clearOne(String uuid) {
        stopOne(uuid);
        DownloadItem downloadItem;
        synchronized (this) {
            DownloadItem found = null;
            for (DownloadItem it : content) {
                if (it != null && uuid.equals(it.getUuid())) {
                    found = it;
                    break;
                }
            }
            if (found == null) return;
            content.remove(found);
            downloadItem = found;
        }
        DownloadingEntity entity = new DownloadingEntity();
        entity.setFileName(downloadItem.getName());
        entity.setUuid(downloadItem.getUuid());
        entity.setTaskGson(Shaft.sGson.toJson(downloadItem));
        AppDatabase.getAppDatabase(mContext).downloadDao().deleteDownloading(entity);
        ManagerReactive.invalidate();
    }

    /**
     * 每次有任务变化（startAll / 一条传输完成 / 用户改并发数）都调一次：把 INIT 队首
     * 拉出来，状态置 DOWNLOADING 后异步派发，直到正在传输的数量达到用户设置的
     * 并发数上限或没有 INIT 可用为止。
     *
     * synchronized 关键：state 检查 + 状态置 DOWNLOADING + handles.put 必须原子，
     * 否则两个 doFinally 同时回调可能挑到同一条 INIT 派发两次。
     *
     * public：用户改并发设置时希望"扩大槽位继续跑"但不希望像 startAll 那样
     * 把手动暂停的 item 也强制恢复 —— FragmentSettings 调这个。
     */
    public synchronized void pumpAvailableSlots() {
        if (!isRunning) return;
        int max = Shaft.sSettings.getMaxConcurrentDownloads();
        if (max < 1) max = 1;
        if (max > 5) max = 5;

        int activeBefore = activeCount();
        int active = activeBefore;
        int dispatched = 0;
        while (active < max) {
            DownloadItem next = getFirstReady();
            if (next == null) break;
            // 抢占式置 DOWNLOADING：阻止下一次 pump 再挑到这条；progress callback
            // 进来再细化为带 nonius 的 DOWNLOADING（语义不变，只是同名状态）。
            next.setState(DownloadItem.DownloadState.DOWNLOADING);
            // 兼容字段：第一个进入的当前下载 = 老 API 返回的 currentIllustID/uuid
            currentIllustID = next.getIllust().getId();
            uuid = next.getUuid();
            downloadOne(mContext, next);
            active++;
            dispatched++;
        }
        // 让用户能在 logcat 里直接核实"并发数设置真的生效"：
        //   [DL-PARALLEL] pump max=5 activeBefore=0 dispatched=5 activeAfter=5
        // → 一次 pump 起了 5 个并行；max 跟 Settings 里设的数字一致就是真在用。
        Common.showLog("[DL-PARALLEL] pump max=" + max
                + " activeBefore=" + activeBefore
                + " dispatched=" + dispatched
                + " activeAfter=" + active);

        if (active == 0 && getFirstReady() == null) {
            // 没活儿了
            isRunning = false;
            Common.showLog("Manager 已经全部下载完成");
        }
        // dispatched > 0 说明刚刚把若干条 INIT 翻成 DOWNLOADING；invalidate 让
        // UI 立刻看到状态翻转（badge / 进度条）。哪怕 dispatched==0 也无所谓，
        // tryEmit 是 cheap idempotent 操作。
        ManagerReactive.invalidate();
    }

    /** 兼容老调用点：等价于 pumpAvailableSlots()。 */
    private void loop() { pumpAvailableSlots(); }

    /** 当前正在传输的 page 数（state=DOWNLOADING 且 !paused）。 */
    private int activeCount() {
        int n = 0;
        for (DownloadItem it : content) {
            if (!it.isPaused() && it.getState() == DownloadItem.DownloadState.DOWNLOADING) n++;
        }
        return n;
    }

    /** 找出可以 dispatch 的下一条：state=INIT 且未暂停。 */
    private DownloadItem getFirstReady() {
        for (DownloadItem it : content) {
            if (!it.isPaused() && it.getState() == DownloadItem.DownloadState.INIT) return it;
        }
        return null;
    }

    private void downloadOne(Context context, DownloadItem downloadItem) {
        Common.showLog("[DL-CACHE] downloadOne enter uuid=" + downloadItem.getUuid()
                + " name=" + downloadItem.getName() + " url=" + downloadItem.getUrl());
        if(!DownloadLimitTypeUtil.canDownloadNow()){
            stopAll();
            return;
        }

        // aria2 远程下载模式（#692）：任务转发给远端 aria2（NAS），不在本地建文件。
        // 必须在 factory 创建之前拦截，否则会平白多出一条本地 MediaStore/SAF 行。
        // 动图（ugoira）不转发 —— zip 是本地播放/合成 GIF 的内部缓存下载，
        // 详见 Aria2Dispatcher.shouldDispatch。
        if (Aria2Dispatcher.shouldDispatch(downloadItem)) {
            dispatchToAria2(downloadItem);
            return;
        }

        Common.showLog("Manager 下载单个 当前进度" + downloadItem.getNonius());

        // SAF factory 创建、文件查询、insert 全部在 IO 线程执行，
        // 避免 172P 连续下载时 SAF 操作阻塞主线程。
        Schedulers.io().scheduleDirect(() -> {
            DownloadFileFactory factory;
            try {
                if (Shaft.sSettings.getDownloadWay() == 0 || downloadItem.getIllust().isGif()) {
                    factory = new Android10DownloadFactory22(context, downloadItem);
                } else {
                    factory = new SAFactory(context, downloadItem);
                }
            } catch (Exception e) {
                Common.showLog("[DL] factory init failed: " + e);
                e.printStackTrace();
                AndroidSchedulers.mainThread().scheduleDirect(() -> {
                    Common.showToast(mContext.getString(R.string.string_365));
                    complete(downloadItem, false);
                    // 单条失败不再 stopAll —— 并发模式下其它正在传的 page 不应受牵连。
                    pumpAvailableSlots();
                });
                return;
            }

            boolean shouldSkip =
                    (factory instanceof Android10DownloadFactory22 && ((Android10DownloadFactory22) factory).isSkip())
                 || (factory instanceof SAFactory && ((SAFactory) factory).isSkip());

            // facade 的 skip 只按**当前模板**渲染出的路径查文件系统。用户升级前用的是
            // 别的命名规则、或记录是 DownloadImporter 从旧版命名的文件扫进来的
            // (issue #953)，那条路径上什么都没有 —— 明明盘上躺着同一张图还是会重下一份，
            // MediaStore 再给它加个 " (1)"。所以这里再按 (illustId, page) 问一次下载记录。
            // 只在策略确实是「已存在则跳过」时生效；Rename 是用户明确要新文件，
            // Replace 本来就要覆写，都不能被记录短路。gif 走的是 app cache 里的中间 zip，
            // 不参与。整段已经在 Schedulers.io() 上，DB + fd 探测都安全。
            if (!shouldSkip && !downloadItem.getIllust().isGif()) {
                boolean skipPolicy =
                        (factory instanceof Android10DownloadFactory22 && ((Android10DownloadFactory22) factory).isSkipPolicy())
                     || (factory instanceof SAFactory && ((SAFactory) factory).isSkipPolicy());
                if (skipPolicy) {
                    shouldSkip = RecordedPageProbe.hasUsableRecord(
                            context,
                            downloadItem.getIllust().getId(),
                            downloadItem.getIndex());
                    if (shouldSkip) {
                        Common.showLog("[DL] skip by download record, illust="
                                + downloadItem.getIllust().getId() + " page=" + downloadItem.getIndex());
                    }
                }
            }

            if (shouldSkip) {
                Common.showLog("[DL] skip download (already exists), illust=" + downloadItem.getIllust().getId());
                complete(downloadItem, true);
                AndroidSchedulers.mainThread().scheduleDirect(() -> {
                    synchronized (Manager.this) {
                        content.remove(downloadItem);
                    }
                    ManagerReactive.invalidate();
                    pumpAvailableSlots();
                });
                return;
            }

            long fileSize = MediaStoreUtil.length(factory.query(), context);
            long passSize = (!downloadItem.shouldStartNewDownload() && fileSize >= 0) ? fileSize : 0;

            Uri targetUri;
            try {
                targetUri = factory.insert();
            } catch (Exception e) {
                Common.showLog("[DL] factory.insert() failed: " + e);
                e.printStackTrace();
                // insert() 内部已自带 cleanup（MediaStoreBackend 在 openOutputStream
                // 失败时会先 delete row 再抛），但 factory 还可能维护额外状态 ——
                // 调一次 abandonWrite 兜底，幂等 + 内部判空。
                try { factory.abandonWrite(); } catch (Exception ignored) {}
                AndroidSchedulers.mainThread().scheduleDirect(() -> {
                    Common.showToast(mContext.getString(R.string.string_365));
                    complete(downloadItem, false);
                    pumpAvailableSlots();
                });
                return;
            }
            if (targetUri == null) {
                Common.showLog("[DL] factory.insert() returned null targetUri");
                try { factory.abandonWrite(); } catch (Exception ignored) {}
                AndroidSchedulers.mainThread().scheduleDirect(() -> {
                    Common.showToast(mContext.getString(R.string.string_365));
                    complete(downloadItem, false);
                    pumpAvailableSlots();
                });
                return;
            }

            final String dlUrl = downloadItem.getUrl();
            final boolean isGif = downloadItem.getIllust().isGif();
            final File cachedFile;
            if (passSize != 0) {
                Common.showLog("[DL-CACHE] skip peek, passSize=" + passSize + " (resume), url=" + dlUrl);
                cachedFile = null;
            } else if (isGif) {
                Common.showLog("[DL-CACHE] skip peek, illust isGif, url=" + dlUrl);
                cachedFile = null;
            } else {
                File peeked = ImageLoaderV3.peekFile(dlUrl);
                if (peeked != null) {
                    Common.showLog("[DL-CACHE] HIT path=" + peeked.getAbsolutePath()
                            + " size=" + peeked.length() + " url=" + dlUrl);
                    cachedFile = peeked;
                } else {
                    Common.showLog("[DL-CACHE] MISS url=" + dlUrl);
                    cachedFile = null;
                }
            }
            // 回主线程启动 RxJava 下载链（handle 赋值需要在一致的线程）
            AndroidSchedulers.mainThread().scheduleDirect(() ->
                startDownloadChain(context, downloadItem, factory, cachedFile, targetUri, dlUrl, passSize));
        });
    }

    /**
     * aria2 远程下载（#692）：把 downloadItem 的 URL 通过 JSON-RPC 发给远端 aria2。
     * RPC 成功 = 这条任务完成（实际下载由远端 aria2 执行）；失败按普通下载失败
     * 处理（FAILED 留在列表里，用户可重试，重试会再次走到这里）。
     *
     * 结构刻意对齐 startDownloadChain：
     *   - disposable 进 handles —— stopAll/stopOne 能取消还没发出去的 RPC，
     *     resurrectIfStranded 也不会把 in-flight 的 RPC 误判成 stranded
     *   - 成功后 content.remove 排主线程 —— QueueDownloadManager.awaitIllustSettled
     *     靠"item 从 content 消失"判断 illust 完成，批量下载因此天然兼容
     *   - 不插 DownloadEntity（已完成 tab）—— 文件不在本地，点开会找不到；
     *     远端下载状态去 aria2 自己的前端（AriaNg 等）看
     */
    private void dispatchToAria2(DownloadItem downloadItem) {
        final String itemUuid = downloadItem.getUuid();
        Disposable d = io.reactivex.Observable.<String>create(emitter -> {
            try {
                String gid = Aria2Dispatcher.dispatch(downloadItem);
                emitter.onNext(gid);
                emitter.onComplete();
            } catch (Exception e) {
                // tryOnError：disposed 后静默丢弃这个 error。isDisposed() 检查与
                // onError() 之间存在 TOCTOU 竞态，若竞态漏网、在 disposed 后普通
                // onError()，会走 Shaft.java 里设的 RxJavaPlugins.setErrorHandler
                // （只记录、不 crash）；tryOnError 是为此设计的竞态安全 API，直接
                // 把这种情况静默掉，更干净。
                emitter.tryOnError(e);
            }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(Schedulers.io())
        .doFinally(() -> {
            handles.remove(itemUuid);
            Common.showLog("[ARIA2] doFinally uuid=" + itemUuid);
            AndroidSchedulers.mainThread().scheduleDirect(this::pumpAvailableSlots);
        })
        .subscribe(gid -> {
            Common.showLog("[ARIA2] dispatched uuid=" + itemUuid + " gid=" + gid
                    + " name=" + downloadItem.getName());
            // 与本地下载成功路径一致：content.remove 第一时间排上主线程
            AndroidSchedulers.mainThread().scheduleDirect(() -> {
                synchronized (Manager.this) {
                    content.remove(downloadItem);
                }
                ManagerReactive.invalidate();
            });
            try { complete(downloadItem, true); } catch (Throwable t) { Common.showLog("[ARIA2] complete(success) failed: " + t); }
            if (Shaft.sSettings.isToastDownloadResult()) {
                AndroidSchedulers.mainThread().scheduleDirect(() ->
                        Common.showToast(mContext.getString(R.string.aria2_task_sent, downloadItem.getName())));
            }
        }, throwable -> {
            Common.showLog("[ARIA2] dispatch failed: " + throwable);
            if (Shaft.sSettings.isToastDownloadResult()) {
                Common.showToast(mContext.getString(R.string.aria2_send_failed, String.valueOf(throwable.getMessage())));
            }
            complete(downloadItem, false);
        });
        handles.put(itemUuid, d);
    }

    private void startDownloadChain(Context context, DownloadItem downloadItem,
            DownloadFileFactory factory, File cachedFile, Uri targetUri, String dlUrl, long passSize) {
        // 下载专用 H1.1 client，规避 H2 stream priority 串行化（详见 getDownloadOkHttpClient）。
        OkHttpClient client = getDownloadOkHttpClient();

        // ─── STAGED-DL 写入策略 ───
        // content:// 目标 (MediaStore / SAF) 直接 openOutputStream 是 Binder pipe，
        // N 流并发写时会被 MediaProvider 串行化：读循环卡 outputStream.write，OkHttp
        // 接收 buffer 满，TCP window 缩 0，**服务器停发那几条流**。N=5 场景的视觉
        // 后果就是 1 条跑满、其余进度近乎不动；首条结束后下一条才接着跑。
        //
        // 修法：先 stream 进 cacheDir/staging_dl/{uuid}.part（本地 FS 写零跨进程
        // 锁），下完一次性 copy 到 targetUri。这样 N 流的字节读取永远不被 MediaStore
        // 阻塞，commit 阶段虽然仍可能串行但只是几十 ms 量级，肉眼无感。
        //
        // 决策维度只有两个：
        //   1. file:// 目标（用户配的纯路径）：无 ContentProvider 介入，永远不需要
        //      staging。
        //   2. maxConc=1：单流场景没有"多写者抢同一个 MediaProvider"的问题，staging
        //      只是凭空多一次本地 copy；省了。
        // 其余情况（content:// + 多并发）一律走 staging —— 不管源是网络流还是
        // Glide 命中的本地缓存。瓶颈在写侧（Binder pipe），跟读侧是网络还是本地无关。
        // 这条修正了 4c16183b 当时只盯网络反压、漏掉缓存命中也共用 MediaProvider
        // 串行点的疏忽（详见 ManagerStagingConcurrencyTest）。
        int maxConc = Shaft.sSettings.getMaxConcurrentDownloads();
        if (maxConc < 1) maxConc = 1;
        if (maxConc > 5) maxConc = 5;
        /**
         * 这里可能需要更好的办法来处理直写的情况下如何做文件上的断点续写
         * 用.part做临时可以省很多事情，毕竟谁也不知道其他系统的特性是否会发生意外的情况
         * .part的好处就是我们一定有权限去读写
         *
         * **/
        // final boolean useStaging = maxConc > 1 && !"file".equals(targetUri.getScheme());
        final boolean useStaging = !"file".equals(targetUri.getScheme());
        final java.io.File stageFile;
        final long effectivePassSize;
        if (useStaging) {
            java.io.File stageDir = new java.io.File(context.getCacheDir(), "staging_dl");
            if (!stageDir.isDirectory() && !stageDir.mkdirs()) {
                Common.showLog("[STAGED-DL] mkdirs failed " + stageDir);
            }
            stageFile = new java.io.File(stageDir, downloadItem.getUuid() + ".part");
            // 入参 passSize 是 MediaStore 行的现有大小（旧续传逻辑），staging 模式
            // 下只看 stage 文件实际长度。同会话 pause/resume 仍能续；冷启动跨会话
            // 的旧 MediaStore 部分文件不再可续，重头来——不大问题。
            //
            // 一个微妙点：续传 stage 依赖 Range 头让服务器跳过 effectivePassSize
            // 字节、剩下的追加到 stage 末尾。本地 cachedFile 路径没 Range 概念，
            // FileInputStream(cachedFile) 永远从 offset 0 读完整字节；这时再用
            // append=true 写 partial stage 就会让 stage 字节翻倍，commit 出去等于
            // 把损坏的图灌进相册。所以本地源永远从空 stage 重来 —— 反正拷贝是 ms
            // 级，没几个字节代价。
            if (passSize > 0 || stageFile.exists()) {
                // 检查 stage 文件是否存在且长度与内存值一致（避免写入错位）
                if (stageFile.exists() && stageFile.length() == passSize) {
                    effectivePassSize = passSize;
                } else if (stageFile.exists() && stageFile.length() > 0) {
                    // 磁盘长度有效，采用磁盘长度
                    effectivePassSize = stageFile.length();
                    downloadItem.setCurrentSize(effectivePassSize);
                    // nonius 暂不更新，等获取 totalSize 后再计算
                    Common.showLog("[STAGED-DL] disk size " + effectivePassSize + " differs from memory " + passSize + ", use disk size");
                }
                else {
                    // 不一致，重置内存并删除旧文件
                    Common.showLog("[STAGED-DL] stage file mismatch, reset");
                    downloadItem.setCurrentSize(0);
                    downloadItem.setNonius(0);
                    if (stageFile.exists()) stageFile.delete();
                    effectivePassSize = 0;
                }
            } else {
                if (stageFile.exists() && !stageFile.delete()) {
                    Common.showLog("[STAGED-DL] stale stage cleanup failed " + stageFile);
                }
                effectivePassSize = 0;
            }
        } else {
            stageFile = null;
            effectivePassSize = passSize;
        }

        // issue #865: cache-miss downloads follow the same image host as
        // on-screen images. downloadItem.getUrl() stays raw everywhere else
        // (identity / dedup / peek key); only the actual network request is
        // rewritten. No-op in the default PIXIV mode.
        Request.Builder reqBuilder = new Request.Builder()
                .url(ceui.lisa.http.ImageHostManager.INSTANCE.rewrite(downloadItem.getUrl()))
                .addHeader(Params.MAP_KEY, Params.IMAGE_REFERER);
        if (effectivePassSize > 0) {
            reqBuilder.addHeader("Range", "bytes=" + effectivePassSize + "-");
        }
        Request request = reqBuilder.build();

        // 并发模式下：每个传输的 disposable 单独存到 handles，按 uuid key
        final String itemUuid = downloadItem.getUuid();
        Disposable d = io.reactivex.Observable.<String>create(emitter -> {
            Response response = null;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            try {
                long contentLength;
                long copyStartNs = System.nanoTime();
                if (cachedFile != null) {
                    Common.showLog("[DL-CACHE] begin local copy, src=" + cachedFile.getAbsolutePath()
                            + " dst=" + targetUri);
                    inputStream = new java.io.FileInputStream(cachedFile);
                    contentLength = cachedFile.length();
                } else {
                    Common.showLog("[DL-CACHE] begin network fetch, url=" + dlUrl
                            + " passSize=" + effectivePassSize + " staging=" + useStaging + " dst=" + targetUri);
                    Call call = client.newCall(request);
                    runningCalls.put(itemUuid, call);
                    try {
                        response = call.execute(); // 改用 call.execute()
                    } finally {
                        runningCalls.remove(itemUuid);
                    }
                    response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        Common.showLog("[DL-CACHE] network HTTP " + response.code() + " url=" + dlUrl);
                        emitter.onError(new IOException("HTTP " + response.code()));
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        Common.showLog("[DL-CACHE] network empty body url=" + dlUrl);
                        emitter.onError(new IOException("Empty response body"));
                        return;
                    }
                    inputStream = body.byteStream();
                    contentLength = body.contentLength();
                }

                long totalSize = contentLength > 0 ? contentLength + effectivePassSize : 0;
                Common.showLog("[DL-CACHE] contentLength=" + contentLength + " totalSize=" + totalSize
                        + " source=" + (cachedFile != null ? "cache" : "network"));

                if (useStaging) {
                    // staging：写本地 cacheDir，effectivePassSize 已基于 stage 文件长度。
                    // 父目录可能在排队期间被系统/「清除缓存」/ 第三方清理软件抹掉
                    //（issue #885），openStageStream 兜底重建一次。续传场景下不重建
                    // ——前 N 字节没法找回，硬接着写会落出截断图。
                    outputStream = openStageStream(stageFile, effectivePassSize);
                } else if ("file".equals(targetUri.getScheme())) {
                    String path = targetUri.getPath();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(path, effectivePassSize > 0);
                    outputStream = fos;
                } else {
                    // 不走 staging：要么 file:// 直写（无 Provider），要么 maxConc=1
                    // 单流（content:// 但没多写者抢 MediaProvider，开 staging 只是
                    // 凭空多一次本地 copy）。两种情况下进 ContentResolver pipe 都安全。
                    outputStream = context.getContentResolver().openOutputStream(targetUri, effectivePassSize > 0 ? "wa" : "w");
                }
                if (outputStream == null) {
                    emitter.onError(new IOException("Cannot open output stream for " + targetUri));
                    return;
                }

                byte[] buffer = new byte[8192];
                long downloaded = effectivePassSize;
                int lastProgress = 0;
                long lastUpdateNs = 0L;
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    if (emitter.isDisposed()) {
                        return;
                    }
                    outputStream.write(buffer, 0, len);
                    downloaded += len;
                    // 进度上报：原版只在 progress %% 跳变时才发，会有两个洞 —
                    //  (a) totalSize=0（响应没 Content-Length，pixiv 有时是 chunked）
                    //      → if 块整个不进，UI 永远 0%；
                    //  (b) 大文件 % 跳变间隔大（如 50MB ≈ 几秒一跳）→ 多 P 之间
                    //      看起来"有的快有的卡"。
                    // 改成：% 跳变 **或** 距上次上报 ≥ 500ms 都强制更新一次；
                    // totalSize=0 时 progress 留 0，但 currentSize/字节数仍会涨，
                    // UI 至少能看到字节在流。
                    long nowNs = System.nanoTime();
                    int progress = totalSize > 0 ? (int) (downloaded * 100 / totalSize) : 0;
                    boolean pctChanged = totalSize > 0 && progress != lastProgress;
                    boolean timeElapsed = (nowNs - lastUpdateNs) > 500_000_000L; // 500ms
                    if (pctChanged || timeElapsed) {
                        lastProgress = progress;
                        lastUpdateNs = nowNs;
                        long finalDownloaded = downloaded;
                        long finalTotal = totalSize;
                        int finalProgress = progress;
                        AndroidSchedulers.mainThread().scheduleDirect(() -> {
                            DownloadProgress dp = new DownloadProgress(finalProgress, finalDownloaded, finalTotal);
                            downloadItem.setNonius(finalProgress);
                            downloadItem.setCurrentSize(finalDownloaded);
                            downloadItem.setTotalSize(finalTotal);
                            downloadItem.setState(DownloadItem.DownloadState.DOWNLOADING);
                            Common.showLog("currentProgress " + finalProgress);
                            // 进度变了 → 让 ManagerReactive.contentFlow 推一帧。
                            // tryEmit 是 cheap，DROP_OLDEST 让高频 progress（5
                            // 并发 ~500/s）自动合并成 collector 能跟上的速率。
                            ManagerReactive.invalidate();
                            try {
                                // 用 item 自己的 uuid 查 callback —— 之前用 Manager 的静态
                                // uuid 字段，并发模式下会拿错（被后启动的覆盖了）。
                                Callback<DownloadProgress> c = getCallback(downloadItem.getUuid());
                                if (c != null) {
                                    c.doSomething(dp);
                                }
                            } catch (Exception e) {
                                Common.showLog("Manager progress callback error: " + e.getMessage());
                            }
                        });
                    }
                }
                outputStream.flush();
                long elapsedMs = (System.nanoTime() - copyStartNs) / 1_000_000L;
                Common.showLog("[DL-CACHE] write done source=" + (cachedFile != null ? "cache" : "network")
                        + " bytes=" + downloaded + " elapsedMs=" + elapsedMs
                        + " staging=" + useStaging + " dst=" + targetUri);

                // STAGED-DL commit：stage → MediaStore Uri 一次性串行 copy，跟别条
                // 下载的字节读循环不再纠缠在 MediaProvider pipe 上。读循环已彻底
                // 走完所以不会再有 TCP 反压风险；commit 串行也只是几十 ms 量级。
                if (useStaging) {
                    try { outputStream.close(); } catch (Exception ignored) {}
                    outputStream = null;  // 让 finally 不重复 close
                    long commitStartNs = System.nanoTime();
                    java.io.FileInputStream stageIn = null;
                    OutputStream mediaOut = null;
                    try {
                        stageIn = new java.io.FileInputStream(stageFile);
                        mediaOut = context.getContentResolver().openOutputStream(targetUri, "w");
                        if (mediaOut == null) {
                            emitter.onError(new IOException("staging commit: openOutputStream null for " + targetUri));
                            return;
                        }
                        byte[] copyBuf = new byte[64 * 1024];
                        int n;
                        while ((n = stageIn.read(copyBuf)) != -1) {
                            if (emitter.isDisposed()) return;
                            mediaOut.write(copyBuf, 0, n);
                        }
                        mediaOut.flush();
                    } finally {
                        if (mediaOut != null) try { mediaOut.close(); } catch (Exception ignored) {}
                        if (stageIn != null) try { stageIn.close(); } catch (Exception ignored) {}
                    }
                    long commitMs = (System.nanoTime() - commitStartNs) / 1_000_000L;
                    Common.showLog("[STAGED-DL] commit done bytes=" + downloaded
                            + " commitMs=" + commitMs + " dst=" + targetUri);
                    if (!stageFile.delete()) {
                        Common.showLog("[STAGED-DL] stage cleanup failed " + stageFile);
                    }
                }

                emitter.onNext(targetUri.toString());
                emitter.onComplete();
            } catch (Exception e) {
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
            } finally {
                if (inputStream != null) try { inputStream.close(); } catch (Exception ignored) {}
                if (outputStream != null) try { outputStream.close(); } catch (Exception ignored) {}
                if (response != null) try { response.close(); } catch (Exception ignored) {}
            }
        })
        .subscribeOn(Schedulers.io())
        // 完成回调保持在 IO 线程，Gson 序列化 + DB 操作 + finishWrite 不阻塞主线程。
        // 只有 UI 通知（广播、Toast）和 pump 回主线程。
        .observeOn(Schedulers.io())
        .doFinally(() -> {
            // 这条传完了，把它的 disposable 从表里移除，主线程上 pump 下一个空闲槽位。
            handles.remove(itemUuid);
            Common.showLog("doFinally uuid=" + itemUuid);
            AndroidSchedulers.mainThread().scheduleDirect(this::pumpAvailableSlots);
        })
        .subscribe(s -> {
            Common.showLog("downloadOne " + s);

            // ===== CRITICAL: 把 content.remove 提到最前 =====
            // QueueDownloadManager.awaitIllustSettled 是靠"item 从 Manager.content
            // 消失"判断 illust 完成的。下面的 Gson 序列化 / Room insert /
            // factory.finishWrite() / complete() 任何一行 throw 都会让原本在
            // 末尾 schedule 的 content.remove **永远不会被 queue 上 main 线程**。
            // 单页文件已经写盘成功（一行字节流早就 flush 了），但 await 看到这个
            // page 还在 content 里 → 永远不 return → consumer 走不到
            // dao.updateStatus(SUCCESS) → 批量队列 list 不消失，最终 stall
            // timeout 把它标 FAILED（FAILED 行 SQL 也不过滤，list 仍然不消失）。
            // 用户看到的就是"图都下成功了 但队列不少"。
            //
            // 把 remove 排到最前面，独立 Runnable，跟后续 IO 工作解耦：哪怕后面
            // 任何步骤 throw，main thread 都已经把这条 item 从 content 取出了。
            AndroidSchedulers.mainThread().scheduleDirect(() -> {
                int sizeBefore;
                boolean removed;
                int sizeAfter;
                synchronized (Manager.this) {
                    sizeBefore = content.size();
                    removed = content.remove(downloadItem);
                    sizeAfter = content.size();
                }
                if (removed) ManagerReactive.invalidate();
                Common.showLog("[DL-REMOVE] remove=" + removed + " sizeBefore=" + sizeBefore
                        + " sizeAfter=" + sizeAfter + " name=" + downloadItem.getName());
            });

            if(downloadItem.getIllust().isGif()){
                Shaft.getMMKV().encode(Params.ILLUST_ID + "_" + downloadItem.getIllust().getId(), true);
                AndroidSchedulers.mainThread().scheduleDirect(() ->
                    PixivOperate.unzipAndPlay(context, downloadItem.getIllust(), downloadItem.isAutoSave()));
            }

            // 下面三块每块独立 try/catch：单条失败别牵连其它。最关键的 content.remove
            // 已经在上面发出去了，这里都是"附加完成动作"，断了任意一条都不至于让
            // 整个 illust 的下载流程卡死。
            DownloadEntity downloadEntity = null;
            try {
                downloadEntity = new DownloadEntity();
                downloadEntity.setIllustGson(Shaft.sGson.toJson(downloadItem.getIllust()));
                downloadEntity.setFileName(downloadItem.getName());
                downloadEntity.setDownloadTime(System.currentTimeMillis());
                downloadEntity.setFilePath(factory.getFileUri().toString());
                // 已知 illust id，直接 set，insertDownload 就不必再解析 illustGson。
                downloadEntity.setIllustId(downloadItem.getIllust().getId());
                // v41 的 page 列：DownloadItem.index 就是 0 基页码。让「已存在则跳过」和
                // 详情页复用本地文件能按 (illustId, page) 查，不依赖文件叫什么名字。
                downloadEntity.setPage(downloadItem.getIndex());
                AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertDownload(downloadEntity);
                Common.showLog("[DL-CACHE] db inserted DownloadEntity fileName=" + downloadEntity.getFileName()
                        + " filePath=" + downloadEntity.getFilePath());
                ManagerReactive.pokeDoneTable();
            } catch (Throwable t) {
                Common.showLog("[DL] DownloadEntity insert failed (file already on disk, skipping 已完成 tab record): " + t);
                downloadEntity = null;
            }

            try { factory.finishWrite(); } catch (Throwable t) { Common.showLog("[DL] finishWrite failed: " + t); }
            // 可选:把作品标签写进 JPEG 的 XMP 关键词(issue #938)。开关默认关,只处理 JPEG,
            // 内部包 try/catch —— 写元数据失败绝不影响这条已成功的下载。gif 会被扩展名过滤掉。
            // 开关前置判断:默认关时这条下载热路径零额外开销(不建 tag 列表、不读文件)。
            try {
                if (Shaft.sSettings.isWriteTagsToImageExif()) {
                    ceui.lisa.models.IllustsBean exifIllust = downloadItem.getIllust();
                    java.util.List<String> exifTags = (exifIllust != null && exifIllust.getTags() != null)
                            ? java.util.Arrays.asList(exifIllust.getTagNames())
                            : java.util.Collections.<String>emptyList();
                    ceui.pixiv.download.ExifKeywordWriter.writeIfEnabled(
                            Shaft.getContext(), factory.getFileUri(), downloadItem.getName(), exifTags);
                }
            } catch (Throwable t) { Common.showLog("[DL] exif keyword write failed: " + t); }
            try { complete(downloadItem, true); } catch (Throwable t) { Common.showLog("[DL] complete(success) failed: " + t); }

            // 广播放第二个 Runnable，跟 content.remove 顺序保留（main thread FIFO）。
            final DownloadEntity finalEntity = downloadEntity;
            AndroidSchedulers.mainThread().scheduleDirect(() -> {
                if (Shaft.sSettings.isToastDownloadResult()) {
                    Common.showToast(downloadItem.getName() + mContext.getString(R.string.has_been_downloaded));
                }
                {
                    Intent intent = new Intent(Params.DOWNLOAD_ING);
                    Holder holder = new Holder();
                    holder.setCode(Params.DOWNLOAD_SUCCESS);
                    holder.setDownloadItem(downloadItem);
                    intent.putExtra(Params.CONTENT, holder);
                    LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                    Common.showLog("[DL-REMOVE] DOWNLOAD_ING broadcast sent");
                }
                if (finalEntity != null) {
                    Intent intent = new Intent(Params.DOWNLOAD_FINISH);
                    intent.putExtra(Params.CONTENT, finalEntity);
                    LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
                }
            });
        }, throwable -> {
            //下载失败，处理相关逻辑
            Common.showLog("Manager download error: " + throwable.getMessage());
            if (Shaft.sSettings.isToastDownloadResult()) {
                Common.showToast("下载失败，原因：" + throwable.toString());
            }
            Common.showLog("下载失败，原因：" + throwable.toString());
            // issue #857：网络抖动 / 断链 → 进这里。之前只 complete + 广播，从不
            // 清理 factory.insert() 阶段创建的 MediaStore 行，导致用户的相册根
            // 目录里堆出大量 0 字节 `.pending-NNNN` 文件。abandonWrite 内部对
            // MediaStore 行做 delete、对 SAF 文件做 delete、对 legacy 文件做 delete
            // （只删自己刚创建的那条；pre-existing 文件不动）。
            try { factory.abandonWrite(); } catch (Exception ignored) {}
            complete(downloadItem, false);
            {
                //通知 DOWNLOAD_ING 有一项下载失败
                Intent intent = new Intent(Params.DOWNLOAD_ING);
                Holder holder = new Holder();
                holder.setCode(Params.DOWNLOAD_FAILED);
                int idx;
                synchronized (Manager.this) {
                    idx = content.indexOf(downloadItem);
                }
                holder.setIndex(idx);
                holder.setDownloadItem(downloadItem);
                intent.putExtra(Params.CONTENT, holder);
                LocalBroadcastManager.getInstance(Shaft.getContext()).sendBroadcast(intent);
            }
        });
        handles.put(itemUuid, d);
    }

    /**
     * 打开 staging .part 文件做写入，兜底 cacheDir 被外部抹掉的情形。
     *
     * Android 在低存储时会主动清 app 的 cacheDir，用户也能从「设置→应用→
     * 清除缓存」一键清掉，第三方清理软件、StorageManager.allocateBytes 触发
     * 的回收同理。批量队列在 PENDING 排队若干分钟到几小时是常态（issue #885
     * 截图里 34 条 PENDING），等真正调度到时 staging_dl 子目录大概率已经被
     * 抹掉。FileOutputStream 不会递归建目录 → ENOENT 直接抛，整批 FAILED。
     *
     * 这里捕到 FNF 后看 effectivePassSize:
     *   - == 0：从头下，request 没带 Range 头。重建父目录后开新文件，零数据
     *     损失。即便 mkdirs 又失败（权限 / 磁盘满），原 FNF 照 throw，外层
     *     onError 走标 FAILED 流程，不会比修前更差。
     *   - >  0：续传，request 已经带 Range:bytes={N}- 头让服务器跳过前 N
     *     字节。父目录没了 = stage 前 N 字节也没了，重建空目录硬接着写会
     *     落出一张 (totalSize - N) 字节的损坏图、commit 进相册。不能救，
     *     直接 throw 让 retry path 再进来一次 —— 下次 stageFile.length()==0
     *     自然走整段重下。
     *
     * package-private 是给同包测试 (ManagerStagingConcurrencyTest) 直接调，
     * 不暴露给外部。
     */
    static java.io.FileOutputStream openStageStream(
            java.io.File stageFile, long effectivePassSize) throws IOException {
        final boolean append = effectivePassSize > 0;
        try {
            return new java.io.FileOutputStream(stageFile, append);
        } catch (java.io.FileNotFoundException fnf) {
            if (append) throw fnf;
            java.io.File parent = stageFile.getParentFile();
            if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                return new java.io.FileOutputStream(stageFile, false);
            }
            throw fnf;
        }
    }

    private String uuid;
    private int currentIllustID;

    public int getCurrentIllustID() {
        return currentIllustID;
    }

    public String getUuid() {
        return uuid;
    }

    private final Map<String, Callback<DownloadProgress>> mCallback = new HashMap<>();

    public Callback<DownloadProgress> getCallback(String uuid) {
        return mCallback.getOrDefault(uuid, null);
    }

    public void clearCallback() {
        mCallback.clear();
    }

    public void setCallback(Callback<DownloadProgress> callback) {
        mCallback.put("", callback);
    }

    public void setCallback(String uuid, Callback<DownloadProgress> callback) {
        mCallback.put(uuid, callback);
    }

    public List<DownloadItem> getContent() {
        return content;
    }

    /**
     * 是否有这个 uuid 的活动 disposable。QueueDownloadManager 的 retry path 用这个
     * 区分"冷启动 Manager.restore 带回的 stranded DOWNLOADING（无 handle，应翻 INIT
     * 重发）"vs"真在跑的 DOWNLOADING（有 handle，**绝不能**翻 INIT，否则 pump 会
     * 再 dispatch 一条 Observable，跟原 chain 抢同一个 stage 文件 / targetUri）"。
     */
    public boolean isRunningHandle(String uuid) {
        return handles.containsKey(uuid);
    }
}
