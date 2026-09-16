package ceui.lisa.core;


import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.DownloadDao;
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
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.api.model.Illust;
import ceui.pixiv.download.RecordedPageProbe;
import ceui.pixiv.download.StageStore;
import ceui.pixiv.download.aria2.Aria2Dispatcher;
import ceui.pixiv.imageloader.ImageLoaderV3;
import ceui.pixiv.progress.ProgressTracker;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;
import timber.log.Timber;

public class Manager {

    private final Context mContext = Shaft.getContext();
    /**
     * 下载列表。用 {@link CopyOnWriteArrayList}：写入（add/remove/clear）仍走
     * {@code synchronized (this)} 保证复合操作原子（去重 + add），但任何线程的遍历
     * （{@link #activeCount}/{@link #getFirstReady}/外部 {@link #getContent()}/
     * {@link #contentSnapshot()}）天然拿到一致快照，不会再撞
     * ConcurrentModificationException。列表规模是「进行中的 page 数」量级，
     * 写时拷贝的代价可忽略。
     */
    private List<DownloadItem> content = new CopyOnWriteArrayList<>();
    /**
     * 下载 IO 线程池（替代原先的 RxJava {@code Schedulers.io()}）：无界缓存池，空闲线程 60s
     * 回收，线程名 {@code shaft-dl-io-N}。restore / addTask 的 DB 写、传输 Body、传输完成后的
     * DB + finishWrite 收尾全在这里跑；UI mutation 一律 {@link #MAIN} post 回主线程。
     */
    private static final ExecutorService IO = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "shaft-dl-io-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });
    /**
     * 主线程投递（替代 {@code AndroidSchedulers.mainThread().scheduleDirect}）。永远 post、
     * 从不同步执行——哪怕当前已在主线程——保持 Rx 的排队语义（多处逻辑依赖"content.remove
     * 先入队、广播第二个 Runnable"这种 FIFO 顺序）。
     */
    private static final class MainHolder {
        // 惰性：JVM 单测里 Looper 未 mock，Manager 的静态工具方法（openStageStream 等）不能因此初始化失败。
        static final Handler MAIN = new Handler(Looper.getMainLooper());
    }

    private static void postMain(Runnable r) {
        MainHolder.MAIN.post(r);
    }
    /**
     * 多任务并发下载的运行句柄表（key = item.uuid），值是 {@link DownloadTask}（替代 Rx
     * {@code Disposable}）。旧设计是单一 handle（串行），现在支持 1-5 并发：每个正在传的
     * page 各占一个 entry。stopOne(uuid) 只 cancel 那一个；stopAll() cancel 全部。
     */
    private final Map<String, DownloadTask> handles = new ConcurrentHashMap<>();
    /**
     * 正在写的 stage key（= sha256(url)）集合。断点续传把 {@code .part} 按 url 命名，
     * url-key 不像 uuid 那样天然唯一（同一张图可能被重复入队），必须挡住两个传输
     * 同时 append 同一个 {@code .part}。{@code startDownloadChain} 抢占，onFinally 释放。
     */
    private final java.util.Set<String> activeStageKeys = ConcurrentHashMap.newKeySet();
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
     * `newBuilder()` 继承全局 client 的 DNS / SSL 等配置，移除图片显示用的 URL 进度
     * 拦截器：Manager 自己在 pumpBytes 上报进度，不能再把独立请求的进度混进显示任务。
     */
    private volatile OkHttpClient mDownloadOkHttpClient;
    private OkHttpClient getDownloadOkHttpClient() {
        OkHttpClient cached = mDownloadOkHttpClient;
        if (cached != null) return cached;
        synchronized (this) {
            if (mDownloadOkHttpClient == null) {
                OkHttpClient base = ((Shaft) Shaft.getContext()).getOkHttpClient();
                mDownloadOkHttpClient = buildDownloadOkHttpClient(
                        base, ((Shaft) Shaft.getContext()).getDownloadProgress());
            }
            return mDownloadOkHttpClient;
        }
    }

    static OkHttpClient buildDownloadOkHttpClient(OkHttpClient base, ProgressTracker displayProgress) {
        OkHttpClient.Builder builder = base.newBuilder()
                .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1));
        builder.networkInterceptors().remove(displayProgress.getInterceptor());
        return builder.build();
    }

    private Manager() {
        uuid = "";
        currentIllustID = 0;
    }

    /**
     * 后台分批读取全部未完成任务，不裁剪持久化队列。Cursor 每次只取一条 JSON，
     * 同作品多 P 共用一个 Illust，避免整库 JSON 和重复作品对象同时驻留内存。
     */
    private static final int RESTORE_BATCH_SIZE = 20;
    private boolean restoreStarted;
    private int restoreGeneration;
    // Guarded by this. Keep URLs even if their live tasks finish/get deleted during recovery.
    private java.util.Set<String> restoreLiveUrls;

    // 字节级限速已彻底删除（debug 调试可视化的 500KB/s 节流）。
    // 唯一保留的是 BulkObjectFetcher.RATE_LIMIT_MS（页间 API 间隔，防 pixiv 429）。

    public synchronized void restore() {
        if (restoreStarted) return;
        restoreStarted = true;
        final int generation = restoreGeneration;
        restoreLiveUrls = content.stream().map(DownloadItem::getUrl).collect(Collectors.toSet());
        IO.execute(() -> {
            boolean failed = false;
            try {
                List<DownloadItem> restored = readRestoredDownloads(
                        AppDatabase.getAppDatabase(mContext).downloadDao(), Shaft.sGson);
                if (restored.isEmpty()) return;
                synchronized (this) {
                    if (generation != restoreGeneration) return; // User cleared the queue.
                    restored.removeIf(item -> restoreLiveUrls.contains(item.getUrl()));
                    restored.addAll(content); // Preserve work added while recovery was running.
                    content = new CopyOnWriteArrayList<>(restored);
                }
                ManagerReactive.invalidate();
                postMain(() ->
                        Common.showToast("下载记录恢复成功"));
            } catch (Throwable t) {
                failed = true;
                Common.showLog("Manager restore failed: " + t.getMessage());
            } finally {
                synchronized (this) {
                    if (generation == restoreGeneration) {
                        restoreLiveUrls = null;
                        if (failed) restoreStarted = false;
                    }
                }
            }
        });
    }

    static List<DownloadItem> readRestoredDownloads(DownloadDao dao, Gson gson) {
        // Match the complete metadata, not just the ID: an artwork may have been edited
        // between two downloads, with different pages or naming-template inputs.
        // Retain a compact digest rather than every artwork's additional JSON object tree.
        Map<ByteString, Illust> artworks = new HashMap<>();
        Gson restoringGson = gson.newBuilder().registerTypeAdapter(Illust.class,
                (JsonDeserializer<Illust>) (json, type, context) ->
                        artworks.computeIfAbsent(ByteString.encodeUtf8(json.toString()).sha256(),
                                key -> gson.fromJson(json, Illust.class))).create();
        List<DownloadItem> restored = new ArrayList<>();
        long after = 0;
        long through = dao.getDownloadingHighWaterMark();
        while (true) {
            try (Cursor cursor = dao.getDownloadingBatch(after, through, RESTORE_BATCH_SIZE)) {
                if (!cursor.moveToFirst()) return restored;
                do {
                    after = cursor.getLong(0);
                    try {
                        DownloadItem item = restoringGson.fromJson(cursor.getString(1), DownloadItem.class);
                        if (item != null && item.getIllust() != null) restored.add(item);
                    } catch (Exception e) {
                        Common.showLog("Manager restore parse error: " + e.getMessage());
                    }
                } while (cursor.moveToNext());
            }
        }
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
                content = new CopyOnWriteArrayList<>();
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
                // 主线程 ANR。故 safeAdd 内部只保证 add 同步、持久化挪后台。
                safeAdd(bean);
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

    /**
     * content.add 同步返回，DownloadingEntity 的持久化(Gson 序列化 + Room insert)丢后台。
     *
     * <p>唯一调用方 {@link #addTask} 在**主线程持锁**时调用：同步写 Room 会卡在被并发
     * hasDownloadRecordByIllustId LIKE 全表扫描占满的 SQLite 连接池上 → 主线程 ANR，
     * 而且持锁期间所有 {@link #contentSnapshot()} 都得排队。批量版 {@link #addTasks}
     * 不走这里——它自己在锁外整批持久化，避免每条 fan-out 一个 IO 任务。
     */
    private void safeAdd(DownloadItem item) {
        Common.showLog("Manager safeAdd " + item.getUuid());
        content.add(item);
        if (restoreLiveUrls != null) restoreLiveUrls.add(item.getUrl());
        final DownloadItem toPersist = item;
        IO.execute(() -> {
            try {
                persistDownloading(toPersist);
            } catch (Throwable t) {
                Common.showLog("safeAdd persistDownloading failed: " + t.getMessage());
            }
        });
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
        IO.execute(() -> {
            long t0 = System.nanoTime();
            // 持久化**必须留在临界区外**：锁住的这段时间里，任何线程的 contentSnapshot()
            // 都得排队等——包括详情页 FAB 在主线程读队列那一下。把 172 次
            // Gson(~80KB)+Room insert 关在锁里，等于让主线程陪着这整批 IO 一起卡（ANR）。
            // 临界区只保留「去重 + content.add」这点纯内存操作，其余挪出去。
            List<DownloadItem> accepted = new ArrayList<>(list.size());
            synchronized (this) {
                if (content == null) {
                    content = new CopyOnWriteArrayList<>();
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
                        // content.add 必须同步:triggerPump 的 getFirstReady 靠 content 立刻变长。
                        content.add(item);
                        if (restoreLiveUrls != null) restoreLiveUrls.add(item.getUrl());
                        existingUrls.add(item.getUrl());
                        accepted.add(item);
                    }
                }
            }
            // 崩溃恢复记录:落后于 content 一小段时间。这段窗口里进程被杀会丢掉「续传」记录
            // (下次冷启少恢复几条)，与 safeAdd 把持久化丢后台是同一个取舍——远比让主线程
            // 陪着整批 IO 一起卡划算。
            for (DownloadItem item : accepted) {
                try {
                    persistDownloading(item);
                } catch (Throwable t) {
                    Common.showLog("addTasks persistDownloading failed: " + t.getMessage());
                }
            }
            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            Common.showLog("[PERF] addTasks total=" + list.size()
                    + " items, totalMs=" + totalMs);
            // batch 完一次 invalidate 即可（DROP_OLDEST 保证多次 tryEmit 自动合并，
            // 但仍是最佳实践：每个语义批次 emit 一次而不是每条 safeAdd 都 emit）
            ManagerReactive.invalidate();
            postMain(() -> {
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
        // 之前 isRunning=true 时会 short-circuit return，假设单线程串行用 onFinally
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
     * 里 [handles].put(uuid) 还在 IO → 主线程 异步排队中的 in-flight item。
     * 走 startAll 会让 resurrectIfStranded 看到"DOWNLOADING && !handles.containsKey"
     * 误判 stranded → setState(INIT) + setNonius(0) → pump 又把它当 INIT 挑出来再
     * dispatch 一次，导致：
     *   - 同 uuid 两条传输任务抢同一个 stage 文件 / targetUri（实测出过两次 read-start）
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
     *     restore 之后 state 字段是 DOWNLOADING，但原 DownloadTask 句柄已随进程消失。
     *     QueueDownloadManager.kt:596 的 retry path 历史上自己处理过这种情况，
     *     现在统一收口到这里。
     *
     * FAILED 也一起翻 INIT，让 retry 自然走 pump 路径。
     * 正在跑的 page（handles 里有 uuid）绝不能动 —— 否则把 DOWNLOADING 翻 INIT 后
     * pump 会再 dispatch 一条传输任务，跟原 chain 抢同一个 stage 文件 / targetUri，
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
        // cancel 全部正在传输的下载（snapshot 防 CME）
        for (DownloadTask d : new ArrayList<>(handles.values())) {
            try { d.cancel(); } catch (Exception ignored) {}
        }
        handles.clear();
        Common.showLog("已经停止");
        ManagerReactive.invalidate();
    }

    public void stopOne(String uuid){
        for (DownloadItem item : contentSnapshot()) {
            if(item.getUuid().equals(uuid)){
                item.setPaused(true);
                Common.showLog("已暂停 " + uuid);
                break;
            }
        }
        DownloadTask d = handles.remove(uuid);
        if (d != null) {
            try { d.cancel(); } catch (Exception ignored) {}
        }
        ManagerReactive.invalidate();
    }

    public void clearAll() {
        stopAll();
        AppDatabase.getAppDatabase(mContext).downloadDao().deleteAllDownloading();
        synchronized (this) {
            restoreGeneration++;
            restoreLiveUrls = null;
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
     * 否则两个 onFinally 同时回调可能挑到同一条 INIT 派发两次。
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
        IO.execute(() -> {
            DownloadFileFactory factory;
            try {
                // SAF 与否只认 V3 下载配置，不读遗留 downloadWay（#984）；
                // 两个 factory 反正都走下载 facade，这里只决定 gif 之外的壳。
                if (!DownloadsRegistry.isSaf() || downloadItem.getIllust().isGif()) {
                    factory = new Android10DownloadFactory22(context, downloadItem);
                } else {
                    factory = new SAFactory(context, downloadItem);
                }
            } catch (Exception e) {
                Common.showLog("[DL] factory init failed: " + e);
                e.printStackTrace();
                postMain(() -> {
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
            // 不参与。整段已经在 IO 线程池上，DB + fd 探测都安全。
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
                postMain(() -> {
                    synchronized (Manager.this) {
                        content.remove(downloadItem);
                    }
                    ManagerReactive.invalidate();
                    pumpAvailableSlots();
                });
                return;
            }

            final String dlUrl = downloadItem.getUrl();
            // content:// 目标（MediaStore / SAF）走 staging + 断点续传：目标行延迟到 commit
            // 才建（见 startDownloadChain / runStagedTransfer）。file:// / gif 走直写。
            final boolean staged = factory.targetIsContent();

            final long passSize;
            final Uri targetUri;
            if (staged) {
                // staged：续传 offset 来自本地 stage（sha256(url) 命名，跨会话稳定），
                // 不看 MediaStore 现有长度；也不现在 insert（避免 0 字节 .pending 泄漏）。
                passSize = 0;
                targetUri = null;
            } else {
                // 直写路径（file:// / gif zip）：保持原逻辑 —— query 现有长度做 append 续传，
                // 现在就 insert 打开目标。
                long fileSize = MediaStoreUtil.length(factory.query(), context);
                passSize = (!downloadItem.shouldStartNewDownload() && fileSize >= 0) ? fileSize : 0;
                Uri opened;
                try {
                    opened = factory.insert();
                } catch (Exception e) {
                    Common.showLog("[DL] factory.insert() failed: " + e);
                    e.printStackTrace();
                    // insert() 内部已自带 cleanup（MediaStoreBackend 在 openOutputStream
                    // 失败时会先 delete row 再抛），但 factory 还可能维护额外状态 ——
                    // 调一次 abandonWrite 兜底，幂等 + 内部判空。
                    try { factory.abandonWrite(); } catch (Exception ignored) {}
                    postMain(() -> {
                        Common.showToast(mContext.getString(R.string.string_365));
                        complete(downloadItem, false);
                        pumpAvailableSlots();
                    });
                    return;
                }
                if (opened == null) {
                    Common.showLog("[DL] factory.insert() returned null targetUri");
                    try { factory.abandonWrite(); } catch (Exception ignored) {}
                    postMain(() -> {
                        Common.showToast(mContext.getString(R.string.string_365));
                        complete(downloadItem, false);
                        pumpAvailableSlots();
                    });
                    return;
                }
                targetUri = opened;
            }

            // 回主线程启动下载链（handle 赋值需要在一致的线程）
            postMain(() ->
                startDownloadChain(context, downloadItem, factory, targetUri, dlUrl, passSize, staged));
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
        DownloadTask d = DownloadTask.launch(IO, emitter -> {
            try {
                String gid = Aria2Dispatcher.dispatch(downloadItem);
                emitter.onNext(gid);
                emitter.onComplete();
            } catch (Exception e) {
                // 取消（disposed）后这个 error 静默丢弃，不会往外抛。
                emitter.tryOnError(e);
            }
        }, gid -> {
            Common.showLog("[ARIA2] dispatched uuid=" + itemUuid + " gid=" + gid
                    + " name=" + downloadItem.getName());
            // 与本地下载成功路径一致：content.remove 第一时间排上主线程
            postMain(() -> {
                synchronized (Manager.this) {
                    content.remove(downloadItem);
                }
                ManagerReactive.invalidate();
            });
            try { complete(downloadItem, true); } catch (Throwable t) { Common.showLog("[ARIA2] complete(success) failed: " + t); }
            if (Shaft.sSettings.isToastDownloadResult() && !downloadItem.isSilent()) {
                postMain(() ->
                        Common.showToast(mContext.getString(R.string.aria2_task_sent, downloadItem.getName())));
            }
        }, throwable -> {
            Common.showLog("[ARIA2] dispatch failed: " + throwable);
            if (Shaft.sSettings.isToastDownloadResult() && !downloadItem.isSilent()) {
                Common.showToast(mContext.getString(R.string.aria2_send_failed, String.valueOf(throwable.getMessage())));
            }
            complete(downloadItem, false);
        }, () -> {
            handles.remove(itemUuid);
            Common.showLog("[ARIA2] onFinally uuid=" + itemUuid);
            postMain(this::pumpAvailableSlots);
        });
        handles.put(itemUuid, d);
    }

    private void startDownloadChain(Context context, DownloadItem downloadItem,
            DownloadFileFactory factory, Uri targetUri, String dlUrl,
            long passSize, boolean staged) {
        // 下载专用 H1.1 client，规避 H2 stream priority 串行化（详见 getDownloadOkHttpClient）。
        OkHttpClient client = getDownloadOkHttpClient();

        // ─── 断点续传 / staging 落点 ───
        // content:// 目标（MediaStore / SAF）一律走 staging（staged=true）：网络字节先写
        // cacheDir/staging_dl/{key}.part，直到 commit 才 factory.insert() 建目标行。好处：
        //   1) 暂停 / 失败 / 进程被杀都不会留下 0 字节 .pending 行 —— 目标行根本还没建；
        //   2) N 流并发写各自的 .part，不被 MediaProvider Binder pipe 串行化；
        //   3) .part 以 sha256(url) 命名（StageStore），跨「失败重试 / 冷启动」稳定可寻回，
        //      配 manifest 里的 validator + Content-Range 校验安全续传（见 runStagedTransfer）。
        // file:// 目标（gif zip / 用户纯路径）走直写路径（staged=false，行为不变）。
        final String itemUuid = downloadItem.getUuid();
        final java.io.File stageDir = staged
                ? new java.io.File(context.getCacheDir(), StageStore.STAGE_DIR_NAME) : null;
        final String stageKey = staged ? StageStore.keyForUrl(dlUrl) : null;
        final java.io.File stageFile = staged ? StageStore.partFile(stageDir, stageKey) : null;
        final java.io.File metaFile = staged ? StageStore.metaFile(stageDir, stageKey) : null;

        // 单主锁：同一个 url 不能有两个传输同时写同一个 .part（url-key 不像 uuid 那样
        // 天然唯一，必须挡住并发）。content 层已按 url 去重，这里是二道保险。抢不到就
        // 让本条失败，交给 pump / 队列重试 —— 那时另一条多半已 commit，record-probe 会 skip。
        final boolean acquiredStage;
        if (staged) {
            acquiredStage = activeStageKeys.add(stageKey);
            if (!acquiredStage) {
                Common.showLog("[STAGED-DL] stage key busy, defer uuid=" + itemUuid + " url=" + dlUrl);
                postMain(() -> {
                    complete(downloadItem, false);
                    pumpAvailableSlots();
                });
                return;
            }
        } else {
            acquiredStage = false;
        }

        DownloadTask d = DownloadTask.launch(IO, emitter -> {
            try {
                // 必须在可取消的 DownloadTask Body 内等待，不能占住主线程，也不能在
                // handles 注册前等待。staged 的本地复制会从 0 覆写，旧 partial 不会混入。
                File cachedFile = null;
                Timber.tag("SharedImageDownload").d("LOOKUP illustId=%d page=%d image=%s",
                        downloadItem.getIllust().getId(), downloadItem.getIndex(),
                        dlUrl.substring(dlUrl.lastIndexOf('/') + 1));
                if (!downloadItem.getIllust().isGif() && (staged || passSize == 0)) {
                    final int[] lastLoggedPercent = {-1};
                    cachedFile = ImageLoaderV3.awaitExistingFile(dlUrl, percent -> {
                        reportProgress(downloadItem, percent, 0L, 0L, false, emitter);
                        int bucket = percent / 10;
                        if (bucket != lastLoggedPercent[0]) {
                            lastLoggedPercent[0] = bucket;
                            Timber.tag("SharedImageDownload").d(
                                    "PROGRESS illustId=%d page=%d percent=%d",
                                    downloadItem.getIllust().getId(), downloadItem.getIndex(), percent);
                        }
                    });
                }
                if (emitter.isDisposed()) return;
                Timber.tag("SharedImageDownload").d(
                        "%s illustId=%d page=%d image=%s",
                        cachedFile != null ? "COPY_SHARED_FILE" : "USE_DOWNLOAD_TRANSFER",
                        downloadItem.getIllust().getId(), downloadItem.getIndex(),
                        dlUrl.substring(dlUrl.lastIndexOf('/') + 1));
                if (staged) {
                    runStagedTransfer(context, downloadItem, factory, cachedFile, dlUrl,
                            stageDir, stageKey, stageFile, metaFile, client, emitter);
                } else {
                    runDirectTransfer(context, downloadItem, cachedFile, targetUri, dlUrl,
                            passSize, client, emitter);
                }
            } catch (Exception e) {
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
            }
        },
        // 完成回调保持在 IO 线程（DownloadTask 在 Body 跑完后同线程交付），Gson 序列化 +
        // DB 操作 + finishWrite 不阻塞主线程。只有 UI 通知（广播、Toast）和 pump 回主线程。
        s -> {
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
            postMain(() -> {
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
                postMain(() ->
                    PixivOperate.unzipAndPlay(context, downloadItem.getIllust(), downloadItem.isAutoSave()));
            }

            // 下面三块每块独立 try/catch：单条失败别牵连其它。最关键的 content.remove
            // 已经在上面发出去了，这里都是"附加完成动作"，断了任意一条都不至于让
            // 整个 illust 的下载流程卡死。
            DownloadEntity downloadEntity = null;
            // 动图这条 DownloadItem 下的是 app cache 里的中间 zip
            // （Android10DownloadFactory22 → LegacyFile.gifZipFile），不是用户拿到的成品。
            // 把它记进「已完成」，卡片的 filePath 就指着一个压缩包，点开等于拿 zip 当图片
            // 解码 —— 一片黑（issue #920）。成品 GIF 由 UgoiraDownloadRecord 在真正编完
            // 落盘后自己记一行；zip 这里不写库、也不发完成广播。
            if (downloadItem.getIllust().isGif()) {
                Common.showLog("[DL] ugoira zip is an intermediate artefact, no 已完成 record");
            } else {
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
            }

            try {
                factory.finishWrite();
                // staged 的 stage（.part + manifest）在 finishWrite 成功后才清：清早了，
                // dispose 恰好丢弃这条 onNext 时（observeOn 跳变窗口）目标行悬挂 pending、
                // stage 又没了，冷启动 cleaner 会把写满的文件当孤儿删掉 → 整段重下。
                // finishWrite 抛错时同理保留 stage —— pending 行终会被 cleaner 回收，
                // 下次尝试凭完整 stage 走 skipNetwork 直接重新 commit。clear 幂等。
                if (staged) {
                    StageStore.clear(stageDir, stageKey);
                }
            } catch (Throwable t) { Common.showLog("[DL] finishWrite failed: " + t); }
            // 可选:把作品标签写进 JPEG 的 XMP 关键词(issue #938)。开关默认关,只处理 JPEG,
            // 内部包 try/catch —— 写元数据失败绝不影响这条已成功的下载。gif 会被扩展名过滤掉。
            // 开关前置判断:默认关时这条下载热路径零额外开销(不建 tag 列表、不读文件)。
            try {
                if (Shaft.sSettings.isWriteTagsToImageExif()) {
                    ceui.pixiv.api.model.Illust exifIllust = downloadItem.getIllust();
                    java.util.List<String> exifTags = (exifIllust != null && exifIllust.getTags() != null)
                            ? exifIllust.getTagNames()
                            : java.util.Collections.<String>emptyList();
                    ceui.pixiv.download.ExifKeywordWriter.writeIfEnabled(
                            Shaft.getContext(), factory.getFileUri(), downloadItem.getName(), exifTags);
                }
            } catch (Throwable t) { Common.showLog("[DL] exif keyword write failed: " + t); }
            try { complete(downloadItem, true); } catch (Throwable t) { Common.showLog("[DL] complete(success) failed: " + t); }

            // 广播放第二个 Runnable，跟 content.remove 顺序保留（main thread FIFO）。
            final DownloadEntity finalEntity = downloadEntity;
            postMain(() -> {
                if (Shaft.sSettings.isToastDownloadResult() && !downloadItem.isSilent()) {
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
            if (Shaft.sSettings.isToastDownloadResult() && !downloadItem.isSilent()) {
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
        }, () -> {
            // 这条传完了：释放 stage 单主锁 + 把句柄从表里移除，主线程上 pump 下一个
            // 空闲槽位。onFinally 对 onNext / onError / cancel(暂停) 都会跑，在这里释放锁最稳。
            if (acquiredStage) {
                activeStageKeys.remove(stageKey);
            }
            handles.remove(itemUuid);
            Common.showLog("onFinally uuid=" + itemUuid);
            postMain(this::pumpAvailableSlots);
        });
        handles.put(itemUuid, d);
    }

    /**
     * staged（content://）传输：网络字节 → 本地 {@code .part} → commit 到目标行。
     *
     * 断点续传的全部安全性都在这里：
     *   1. {@code .part} 已有字节数 = 续传 offset。带 {@code Range: bytes=N-}；有
     *      validator（ETag/Last-Modified）再带 {@code If-Range}，让服务器一个来回内
     *      原子决定「资源没变→206 续传 / 变了→200 全量」。
     *   2. 拿到响应后先经 {@link StageStore#decideWrite} 判定，**绝不盲目 append**：
     *      - 200：服务器无视 Range（或首次），从 offset 0 覆写 {@code .part}；
     *      - 206 且 Content-Range 起点 == 已有字节：追加；
     *      - 206 起点对不上 / 416 字节矛盾：ABORT，弃 {@code .part} 整段重下；
     *      - 416 且已持有全部字节：直接提交现有 {@code .part}。
     *   3. commit 时才 {@link DownloadFileFactory#insert()} 建目标行 —— 中途暂停 / 失败
     *      不会留下 0 字节 {@code .pending}。{@code .part} + manifest 在订阅者侧
     *      {@code finishWrite()} 成功后才删（见 startDownloadChain 的 onNext 消费块）。
     *
     * 暂停 / 取消（{@code emitter.isDisposed()}）时**保留** {@code .part} + manifest，
     * 供下次续传；只有 finishWrite 成功或 ABORT 才清理。
     */
    private void runStagedTransfer(Context context, DownloadItem downloadItem,
            DownloadFileFactory factory, File cachedFile, String dlUrl,
            java.io.File stageDir, String stageKey, java.io.File stageFile, java.io.File metaFile,
            OkHttpClient client, DownloadEmitter emitter) throws Exception {
        Response response = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;
        // commit 前的完整性基线：本次落盘后 .part 理应有多少字节。-1 = 未知（chunked /
        // 无 Content-Length）时跳过校验。见下方 commit 前的 stageFile.length() 比对。
        long expectedTotal = -1L;
        try {
            if (cachedFile != null) {
                // 缓存命中：本地已有完整字节 → 从 0 覆写 stage（不续传），删旧 manifest。
                Common.showLog("[DL-CACHE] staged local copy, src=" + cachedFile.getAbsolutePath());
                runCatchingDelete(metaFile);
                inputStream = new java.io.FileInputStream(cachedFile);
                outputStream = openStageStream(stageFile, 0);
                expectedTotal = cachedFile.length();
                pumpBytes(inputStream, outputStream, downloadItem, 0L, expectedTotal, true, emitter);
                if (emitter.isDisposed()) return;
                closeQuietly(outputStream); outputStream = null;
                closeQuietly(inputStream); inputStream = null;
            } else {
                long existingLen = stageFile.length();
                StageStore.Manifest mf = existingLen > 0 ? StageStore.readManifest(metaFile) : null;

                // 预检：manifest 已知总长时，不必发网络请求就能判断 partial 状态，省一次
                // 往返、也不依赖服务器 416 一定带 Content-Range。
                boolean skipNetwork = false;
                if (mf != null && mf.total >= 0) {
                    if (existingLen == mf.total) {
                        // 字节已齐（上次 commit 前断了 / commit 自身失败）→ 直接提交。
                        skipNetwork = true;
                        expectedTotal = mf.total;
                        Common.showLog("[STAGED-DL] stage already complete (" + existingLen + "B), commit without fetch");
                    } else if (existingLen > mf.total) {
                        // partial 比总长还大 = 脏数据，整段重下。
                        Common.showLog("[STAGED-DL] stage overshoot len=" + existingLen
                                + " > total=" + mf.total + ", reset");
                        StageStore.clear(stageDir, stageKey);
                        existingLen = 0;
                        mf = null;
                    }
                }

                if (!skipNetwork) {
                    // issue #865: 只有真正发出去的网络请求换 host；dlUrl 在别处（dedup / peek /
                    // stage key）保持原值。默认 PIXIV 模式下 rewrite 是 no-op。
                    Request.Builder rb = new Request.Builder()
                            .url(ceui.lisa.http.ImageHostManager.INSTANCE.rewrite(dlUrl))
                            .addHeader(Params.MAP_KEY, Params.IMAGE_REFERER);
                    if (existingLen > 0) {
                        rb.addHeader("Range", "bytes=" + existingLen + "-");
                        if (mf != null && mf.validator != null
                                && (StageStore.VALIDATOR_ETAG.equals(mf.validatorType)
                                    || StageStore.VALIDATOR_LASTMOD.equals(mf.validatorType))) {
                            rb.addHeader("If-Range", mf.validator);
                        }
                    }
                    Common.showLog("[STAGED-DL] fetch url=" + dlUrl + " existing=" + existingLen);
                    Timber.tag("SharedImageDownload").d("NETWORK illustId=%d page=%d resumeBytes=%d",
                            downloadItem.getIllust().getId(), downloadItem.getIndex(), existingLen);
                    response = client.newCall(rb.build()).execute();
                    int code = response.code();
                    // 416 也要放行进 decideWrite（可能意味着"字节已齐"）。
                    if (!response.isSuccessful() && code != 416) {
                        emitter.onError(new IOException("HTTP " + code));
                        return;
                    }
                    ResponseBody body = response.body();
                    long contentLength = body != null ? body.contentLength() : -1L;
                    StageStore.WriteDecision dec = StageStore.decideWrite(
                            code, response.header("Content-Range"), contentLength, existingLen);
                    Common.showLog("[STAGED-DL] code=" + code + " mode=" + dec.mode
                            + " startOffset=" + dec.startOffset + " total=" + dec.total);

                    if (dec.mode == StageStore.WriteMode.ABORT) {
                        // 无法安全续传：弃掉 partial + manifest，抛错让队列 retry 走整段重下。
                        StageStore.clear(stageDir, stageKey);
                        emitter.onError(new IOException("resume aborted (code=" + code + "), restart fresh"));
                        return;
                    }
                    // FRESH / APPEND / ALREADY_COMPLETE 落盘后 .part 理应等于 dec.total。
                    expectedTotal = dec.total;
                    if (dec.mode == StageStore.WriteMode.FRESH || dec.mode == StageStore.WriteMode.APPEND) {
                        if (body == null) {
                            emitter.onError(new IOException("Empty response body"));
                            return;
                        }
                        // 先落 manifest（validator + total）：哪怕这次中途断了，下次也能凭
                        // If-Range 安全续传。
                        StageStore.writeManifest(metaFile, StageStore.buildManifest(
                                dlUrl, response.header("ETag"), response.header("Last-Modified"), dec.total));
                        boolean append = dec.mode == StageStore.WriteMode.APPEND;
                        inputStream = body.byteStream();
                        outputStream = openStageStream(stageFile, append ? dec.startOffset : 0);
                        pumpBytes(inputStream, outputStream, downloadItem, dec.startOffset, dec.total, false, emitter);
                        if (emitter.isDisposed()) return;  // 暂停 / 取消：保留 stage 续传
                        closeQuietly(outputStream); outputStream = null;
                        closeQuietly(inputStream); inputStream = null;
                    }
                    // ALREADY_COMPLETE：字节已在 stage 里，直接进 commit。
                    closeQuietly(response); response = null;
                }
            }

            // —— commit 前的完整性闸门 ——
            // .part 落盘长度必须等于已知总长，否则 commit 出去就是截断图。网络短读会被
            // OkHttp 的 premature-EOF 抛错拦在前面；这里兜住的是本地侧的隐性截断 ——
            // 典型：续传窗口内 cacheDir 被「清除缓存」/ 第三方清理软件抹掉 .part（目录还在），
            // openStageStream 的 append 模式会**静默重建一个空文件**只写 tail，字节数就少了
            // 前缀那一段。这类 .part 无法安全续传（不知道缺哪几段），直接清掉整段重下。
            // total 未知（chunked / 无 Content-Length，expectedTotal<0）时无从校验，放行。
            if (expectedTotal >= 0) {
                long stagedLen = stageFile.length();
                if (stagedLen != expectedTotal) {
                    Common.showLog("[STAGED-DL] length mismatch stagedLen=" + stagedLen
                            + " != expected=" + expectedTotal + ", reset + restart");
                    StageStore.clear(stageDir, stageKey);
                    emitter.onError(new IOException(
                            "stage length mismatch: " + stagedLen + " != " + expectedTotal));
                    return;
                }
            }

            // —— commit：延迟 insert 目标行，写 stage → 目标；成功后删 stage + manifest ——
            Uri targetUri = factory.insert();
            if (targetUri == null) {
                emitter.onError(new IOException("staging commit: factory.insert() returned null"));
                return;
            }
            commitStageToTarget(context, stageFile, targetUri, emitter);
            if (emitter.isDisposed()) {
                // 暂停 / 取消正好落在 commit 拷贝窗口内（insert() 已建目标行，但还没
                // finishWrite 清 IS_PENDING）。不收口的话这条 partial pending 行会一直
                // 悬挂到下次冷启动才被 cleanupPendingOrphans 扫掉 —— 现在就 abandonWrite
                // 删掉它（只删本次 insert 刚建的那条，pre-existing 不动）。stage .part
                // **不清**，留给续传：下次 stage 已齐 → skipNetwork → 重新 insert + commit。
                // 注：进程被杀（非 dispose）走不到这里，那种残留仍靠冷启动 cleaner 兜底。
                try { factory.abandonWrite(); } catch (Exception ignored) {}
                return;
            }
            // stage 的清理**不在这里做**：onNext 与订阅者消费块之间隔着一次 observeOn(io)
            // 调度跳变，dispose 落在这个窗口会把 onNext 静默丢掉 —— 若此时 stage 已清，
            // 这条 IS_PENDING=1 的目标行没人 finishWrite，下次冷启动被 cleanupPendingOrphans
            // 连文件一起删掉，一张已下完的图就白丢了。清理挪到订阅者侧 finishWrite 成功
            // 之后（见 startDownloadChain 的 onNext 消费块）：onNext 被丢弃时 stage 仍在，
            // 下次尝试走 skipNetwork → 重新 insert + commit，零字节浪费。
            emitter.onNext(targetUri.toString());
            emitter.onComplete();
        } finally {
            closeQuietly(outputStream);
            closeQuietly(inputStream);
            closeQuietly(response);
        }
    }

    /**
     * 直写（file:// / gif zip）传输：保持历史行为不变 —— {@code passSize>0} 时带 Range
     * 头 + append 模式续写目标文件，无 manifest / validator（file:// 没有 ContentProvider
     * 的半成品窗口，暂停即续；跨失败续传只有 staged content:// 路径提供）。
     */
    private void runDirectTransfer(Context context, DownloadItem downloadItem, File cachedFile,
            Uri targetUri, String dlUrl, long passSize, OkHttpClient client,
            DownloadEmitter emitter) throws Exception {
        Response response = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            long contentLength;
            if (cachedFile != null) {
                Common.showLog("[DL-CACHE] direct local copy, src=" + cachedFile.getAbsolutePath()
                        + " dst=" + targetUri);
                inputStream = new java.io.FileInputStream(cachedFile);
                contentLength = cachedFile.length();
            } else {
                Request.Builder rb = new Request.Builder()
                        .url(ceui.lisa.http.ImageHostManager.INSTANCE.rewrite(dlUrl))
                        .addHeader(Params.MAP_KEY, Params.IMAGE_REFERER);
                if (passSize > 0) {
                    rb.addHeader("Range", "bytes=" + passSize + "-");
                }
                Timber.tag("SharedImageDownload").d("NETWORK illustId=%d page=%d resumeBytes=%d",
                        downloadItem.getIllust().getId(), downloadItem.getIndex(), passSize);
                response = client.newCall(rb.build()).execute();
                if (!response.isSuccessful()) {
                    emitter.onError(new IOException("HTTP " + response.code()));
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    emitter.onError(new IOException("Empty response body"));
                    return;
                }
                inputStream = body.byteStream();
                contentLength = body.contentLength();
            }

            long totalSize = contentLength > 0 ? contentLength + passSize : 0;
            if ("file".equals(targetUri.getScheme())) {
                outputStream = new java.io.FileOutputStream(targetUri.getPath(), passSize > 0);
            } else {
                outputStream = context.getContentResolver()
                        .openOutputStream(targetUri, passSize > 0 ? "wa" : "w");
            }
            if (outputStream == null) {
                emitter.onError(new IOException("Cannot open output stream for " + targetUri));
                return;
            }
            pumpBytes(inputStream, outputStream, downloadItem, passSize, totalSize, cachedFile != null, emitter);
            if (emitter.isDisposed()) return;
            closeQuietly(outputStream); outputStream = null;
            emitter.onNext(targetUri.toString());
            emitter.onComplete();
        } finally {
            closeQuietly(outputStream);
            closeQuietly(inputStream);
            closeQuietly(response);
        }
    }

    /**
     * 8KB buffer 拷贝主循环 + 节流进度上报。两条传输路径共用。
     *
     * {@code startOffset} 是已下字节基线（续传时 > 0）；进度按 {@code totalSize} 算，
     * {@code totalSize<=0}（chunked / 无 Content-Length）时 progress 留 0 但字节数照涨。
     * {@code emitter.isDisposed()}（暂停 / 取消）时**立即 return，不 flush** —— 已写进
     * {@code .part} 的字节留在盘上正是续传要的；调用方据 {@code isDisposed()} 跳过 commit。
     *
     * @return 实际写到的总字节数（含 startOffset）。
     */
    private long pumpBytes(InputStream in, OutputStream out, DownloadItem item,
            long startOffset, long totalSize, boolean localCopy, DownloadEmitter emitter) throws IOException {
        byte[] buffer = new byte[8192];
        long downloaded = startOffset;
        int lastProgress = 0;
        long lastUpdateNs = 0L;
        int len;
        while ((len = in.read(buffer)) != -1) {
            if (emitter.isDisposed()) {
                return downloaded;
            }
            out.write(buffer, 0, len);
            downloaded += len;
            long nowNs = System.nanoTime();
            int progress = totalSize > 0 ? (int) (downloaded * 100 / totalSize) : 0;
            boolean pctChanged = totalSize > 0 && progress != lastProgress;
            boolean timeElapsed = (nowNs - lastUpdateNs) > 500_000_000L; // 500ms
            if (pctChanged || timeElapsed) {
                lastProgress = progress;
                lastUpdateNs = nowNs;
                reportProgress(item, progress, downloaded, totalSize, localCopy, emitter);
            }
        }
        out.flush();
        return downloaded;
    }

    private void reportProgress(DownloadItem item, int percent, long downloaded, long total,
            boolean localCopy, DownloadEmitter emitter) {
        if (emitter.isDisposed()) return;
        postMain(() -> {
            // 暂停/重试以后迟到的共享进度不能把旧任务重新标成 DOWNLOADING。
            if (emitter.isDisposed()) return;
            // 显示任务可能已到 99%；随后本地复制不能把下载列表的进度拉回 0%。
            int progress = localCopy ? Math.max(item.getNonius(), percent) : percent;
            item.setNonius(progress);
            item.setCurrentSize(downloaded);
            item.setTotalSize(total);
            item.setState(DownloadItem.DownloadState.DOWNLOADING);
            ManagerReactive.invalidate();
            try {
                Callback<DownloadProgress> callback = getCallback(item.getUuid());
                if (callback != null) callback.doSomething(new DownloadProgress(progress, downloaded, total));
            } catch (Exception e) {
                Common.showLog("Manager progress callback error: " + e.getMessage());
            }
        });
    }

    /**
     * stage → 目标 Uri 一次性串行 copy。读循环已走完，不再有 TCP 反压；commit 串行
     * 只是几十 ms。{@code emitter.isDisposed()} 时中途 return（不删 stage，留给续传）。
     */
    private void commitStageToTarget(Context context, java.io.File stageFile, Uri targetUri,
            DownloadEmitter emitter) throws IOException {
        long t0 = System.nanoTime();
        java.io.FileInputStream stageIn = null;
        OutputStream mediaOut = null;
        try {
            stageIn = new java.io.FileInputStream(stageFile);
            mediaOut = context.getContentResolver().openOutputStream(targetUri, "w");
            if (mediaOut == null) {
                throw new IOException("staging commit: openOutputStream null for " + targetUri);
            }
            byte[] copyBuf = new byte[64 * 1024];
            int n;
            while ((n = stageIn.read(copyBuf)) != -1) {
                if (emitter.isDisposed()) return;
                mediaOut.write(copyBuf, 0, n);
            }
            mediaOut.flush();
        } finally {
            closeQuietly(mediaOut);
            closeQuietly(stageIn);
        }
        Common.showLog("[STAGED-DL] commit done ms=" + (System.nanoTime() - t0) / 1_000_000L
                + " dst=" + targetUri);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private static void runCatchingDelete(java.io.File f) {
        if (f != null) {
            try { f.delete(); } catch (Exception ignored) {}
        }
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
    private long currentIllustID;

    public long getCurrentIllustID() {
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
     * 再 dispatch 一条传输任务，跟原 chain 抢同一个 stage 文件 / targetUri）"。
     */
    public boolean isRunningHandle(String uuid) {
        return handles.containsKey(uuid);
    }
}
