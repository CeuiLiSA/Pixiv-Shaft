package ceui.lisa.activities;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;

import com.blankj.utilcode.util.BarUtils;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.gson.Gson;
import com.hjq.toast.Toaster;

import com.getkeepsafe.relinker.ReLinker;
import com.tencent.mmkv.MMKV;

import androidx.annotation.NonNull;

import ceui.lisa.R;

import ceui.lisa.helper.ShortcutHelper;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.notification.NetWorkStateReceiver;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Settings;
import ceui.lisa.viewmodel.AppLevelState;
import ceui.loxia.ServicesProvider;
import ceui.pixiv.db.EntityWrapper;
import ceui.pixiv.session.SessionManager;
import ceui.pixiv.utils.NetworkStateManager;
import me.jessyan.progressmanager.ProgressManager;
import okhttp3.OkHttpClient;
import timber.log.Timber;

import static ceui.lisa.utils.Local.LOCAL_DATA;

import org.jetbrains.annotations.NotNull;

/**
 * Where the app code starts.
 * */
public class Shaft extends Application implements ServicesProvider {

    public static Settings sSettings;
    public static Gson sGson;
    public static SharedPreferences sPreferences;
    protected NetWorkStateReceiver netWorkStateReceiver;
    private NetworkStateManager networkStateManager;
    private OkHttpClient mOkHttpClient;
    private static MMKV mmkv;
    private AppLevelState appLevelState;
    private ceui.pixiv.ui.translate.MangaTranslateModels mangaTranslateModels;
    private ceui.pixiv.ui.translate.MangaBatchTranslateCenter mangaBatchTranslateCenter;
    private ceui.loxia.FanboxWebBridge fanboxWebBridge;
    private ceui.pixiv.ui.bulk.QueueDownloadManager queueDownloadManager;
    private ceui.pixiv.actions.PixivActionQueue pixivActionQueue;
    private ceui.pixiv.db.discovery.ProfileManager profileManager;
    private ceui.pixiv.db.discovery.DiscoveryPool discoveryPool;
    private ceui.pixiv.actions.AccountOnlineReportOutbox accountOnlineReportOutbox;
    private ceui.pixiv.actions.Nana7miSearchTelemetry nana7miSearchTelemetry;
    private ceui.pixiv.config.RemoteAppConfig remoteAppConfig;
    private ceui.pixiv.events.EventReporter eventReporter;

    private EntityWrapper entityWrapper;

    /**
     * 主线程一直不 idle 时的兜底延时，只为「永远等不到 idle」这个病态情况存在。
     *
     * <p>取 5 秒是量出来的：Pixel 8 上首帧落在 ~1.2s，IdleHandler 紧跟其后回调，
     * 兜底根本不会触发。取过 1 秒，结果是兜底反而抢在首帧之前跑，等于没延迟——
     * 这个值必须明显大于首帧耗时，否则整个延迟批白做。
     */
    private static final long DEFERRED_INIT_BACKSTOP_MS = 5000L;
    /** 旧 widget 的 WorkManager 残留已清理过的标志，见 {@link #runDeferredInit()}。 */
    private static final String LEGACY_WIDGET_WORK_CLEARED_KEY = "legacy_widget_work_cleared_v1";

    /** 见 {@link #runDeferredInit()}。只在主线程读写。 */
    private boolean deferredInitDone = false;
    /**
     * 最近一个 created / resumed 的 Activity，仅供 {@link #runDeferredInit()} 里
     * InAppBanners 补装 banner 宿主用。onActivityDestroyed 里清掉，不会漏 Activity。
     */
    @SuppressLint("StaticFieldLeak")
    private Activity currentActivity;

    /**
     * 全局context
     */
    @SuppressLint("StaticFieldLeak")
    private static Context sContext = null;

    public static Context getContext() {
        return sContext;
    }

    /**
     * 在 super.attachBaseContext 之前：预热 SharedPreferences（见 [prefetchPreferences]）、
     * init MMKV，再用 [AppLocales.wrapWithSavedLocale] 包出正确 locale 的 base context。
     *
     * 三者顺序有意义：预热排最前，它只是「起个后台加载线程」，紧随其后的 MMKV native 初始化
     * 和 locale 包装正好用来盖住那次磁盘读。
     *
     * 影响：Application Context 的 Resources 从进程启动就是正确 locale —— 任何走
     * `Shaft.sApplicationContext.getString(...)` / `Common.showToast(...)` 之类的代码路径在
     * Application Context 上拿 string 都直接是用户选定的语言，不需要等下次冷启再补。
     *
     * MMKV.initialize 提前到 attachBaseContext 是 OK 的：onCreate 里那次重复调用幂等无害；
     * Pixiv-Shaft 的 ContentProvider 只有 androidx FileProvider，不依赖 MMKV，无时序冲突。
     *
     * 出错绝对吞掉 —— attachBaseContext 抛异常会让整个进程起不来。
     */
    @Override
    protected void attachBaseContext(Context base) {
        try {
            prefetchPreferences(base);
        } catch (Throwable ignored) {
            // onCreate 里有 null 兜底，纯预热失败不该影响启动。
        }
        try {
            initMMKV(base);
        } catch (Throwable ignored) {
            // Application.onCreate 里还会再 init 一次兜底。
        }
        super.attachBaseContext(ceui.pixiv.i18n.AppLocales.INSTANCE.wrapWithSavedLocale(base));
    }

    /**
     * 尽可能早地把 local_data 这份 SharedPreferences 建出来，只为触发它的后台加载。
     *
     * <p>{@code SharedPreferencesImpl} 的构造函数里就会起线程去读、解析 XML，而所有
     * getter 都要在一个 latch 上等它读完。实测 {@code Local.getSettings()} 里那次
     * {@code getString} 有 ~42ms 花在等这个 latch 上（Gson 解析本身只占 ~15ms）——
     * 也就是说主线程绝大部分时间是在干等磁盘，不是在算。
     *
     * <p>提到 attachBaseContext 最前面，加载线程就能和后面的 MMKV native 初始化、
     * locale 包装、Gson 类加载并行跑，等主线程真的要读时 latch 多半已经放开。
     *
     * <p>拿 {@code base} 取和之后拿 Application 取的是同一个对象：
     * {@code SharedPreferencesImpl} 缓存是 ContextImpl 里按「包名 + 文件」索引的静态表，
     * 同进程同包必然命中同一份，这里预热的正是稍后要用的那个实例。
     */
    private static void prefetchPreferences(Context base) {
        sPreferences = base.getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
    }

    /**
     * 用 ReLinker 兜底加载 libmmkv.so。部分华为等 OEM 上系统默认 lib 路径里 dlopen 会报
     * "libmmkv.so not found"（应用分身/存储清理把解压出来的 .so 弄没了、或安装时未正确解压），
     * 直接 MMKV.initialize 抛 UnsatisfiedLinkError 把启动整崩。ReLinker 先走正常 System.loadLibrary，
     * 失败才从 APK 抠出 .so 复制到私有目录按绝对路径加载——健康设备零行为变化，坏设备能救回来。
     */
    private static void initMMKV(Context context) {
        MMKV.initialize(context, libName -> ReLinker.loadLibrary(context, libName));
    }

    private static boolean hasStackFrame(Throwable t, String classNamePrefix) {
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith(classNamePrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Initialize the whole application.
     * */
    @Override
    public void onCreate() {
        super.onCreate();

        // Keep the main Looper alive across the GMS "Unknown calling package name"
        // SecurityException. GMS delivers it on the main thread's Handler, which
        // otherwise unwinds Looper.loop() and kills the process. Re-entering the
        // loop from a catch block is the only way to actually suppress it — a
        // Thread.defaultUncaughtExceptionHandler runs too late.
        new Handler(Looper.getMainLooper()).postAtFrontOfQueue(() -> {
            while (true) {
                try {
                    Looper.loop();
                    return;
                } catch (Throwable t) {
                    if (t instanceof SecurityException
                            && t.getMessage() != null
                            && t.getMessage().contains("Unknown calling package name")) {
                        Timber.w(t, "Suppressed GMS SecurityException on main thread");
                        continue;
                    }
                    // 另一类 GMS 主线程 SecurityException：某些 ROM 上 Play Services
                    // 内部去注册 PermissionManager.OnPermissionsChangedListener，需要系统级
                    // OBSERVE_GRANT_REVOKE_PERMISSIONS 权限，普通应用拿不到，于是 GMS 在
                    // BaseGmsClient 的 onReportServiceBinding 回调里直接把它抛到主线程
                    // Handler，host app 没有任何 API 能阻止。permission 名字够独特，但仍要求
                    // stack 里有 GMS 帧才放过，否则会吞掉应用自己抛的 SecurityException。
                    if (t instanceof SecurityException
                            && t.getMessage() != null
                            && t.getMessage().contains("OBSERVE_GRANT_REVOKE_PERMISSIONS")
                            && hasStackFrame(t, "com.google.android.gms")) {
                        Timber.w(t, "Suppressed GMS permission-listener SecurityException on main thread");
                        continue;
                    }
                    if (t instanceof RuntimeException
                            && t.getMessage() != null
                            && t.getMessage().contains("trying to draw too large")) {
                        Timber.w(t, "Suppressed oversized bitmap draw on main thread");
                        continue;
                    }
                    // Glide GifDrawable race: GifFrameLoader 回收上一帧 bitmap 后，
                    // ImageView 还在用旧的 GifDrawable 跑一次 onDraw，于是
                    // canvas.drawBitmap(null,...) → BaseCanvas.throwIfCannotDraw NPE。
                    // 上游 issue 长期未修，丢一帧好过崩进程。stack 里必须有
                    // GifDrawable 帧才放过，否则会吞掉无关的 NPE。
                    if (t instanceof NullPointerException
                            && t.getMessage() != null
                            && t.getMessage().contains("Bitmap.isRecycled()")
                            && hasStackFrame(t, "com.bumptech.glide.load.resource.gif.GifDrawable")) {
                        Timber.w(t, "Suppressed Glide GifDrawable null-bitmap draw on main thread");
                        continue;
                    }
                    // android.widget.Magnifier.getPosition() NPE：读 selectable/editable
                    // TextView 时长按拖动选择手柄会弹出系统文本放大镜（Magnifier）。拖动过程中
                    // view 被 detach / 布局重算，框架内部的 source Rect 变 null，下一帧放大镜位置
                    // 更新（走主线程 Handler 回调，正是本处重入的 Looper.loop）就在 getPosition()
                    // 里读 Rect.left NPE。小说阅读器 ReaderTextBlockView / NovelScrollReaderView
                    // 开了 setTextIsSelectable(true)，是唯一触发路径；放大镜是框架内部对象，host app
                    // 没有公开 API 能按 view 关掉它，也无法阻止这个 race。丢一帧放大镜好过崩进程——
                    // 下次拖动会重建。用 stack 帧判定而非 message：ART 的 helpful-NPE 文案随 API
                    // 版本变，而 android.widget.Magnifier 里绝不会跑应用自己的代码，帧判定既稳又
                    // 绝不会吞掉 app 自身的 NPE。
                    if (t instanceof NullPointerException
                            && hasStackFrame(t, "android.widget.Magnifier")) {
                        Timber.w(t, "Suppressed text-selection Magnifier NPE on main thread");
                        continue;
                    }
                    // RecyclerView "Inconsistency detected. Invalid view holder adapter
                    // position"：predictive-animation 预布局(dispatchLayoutStep1)在
                    // SmartRefreshLayout + ViewPager 快速滑动 / 重入布局下，RecyclerView 内部
                    // 把 pending insert 和 scrap holder 的偏移对不上时抛的 IOOBE。我们的
                    // notifyItemRangeInserted 计数(beforeLoadSize→afterLoadSize 差值)是对的，
                    // host app 在数据层无法阻止这个框架内部 bug。它会自愈：坏的这帧布局回退后，
                    // 下一帧 Choreographer 重新 onLayout 会按 getItemCount() 干净重建。丢一帧好过
                    // 崩进程。message 文本是 AOSP RecyclerView 写死的，且要求 stack 里有
                    // RecyclerView 帧，绝不会吞掉应用自己抛的 IndexOutOfBoundsException。
                    if (t instanceof IndexOutOfBoundsException
                            && t.getMessage() != null
                            && t.getMessage().contains("Inconsistency detected")
                            && hasStackFrame(t, "androidx.recyclerview.widget.RecyclerView")) {
                        Timber.w(t, "Suppressed RecyclerView layout inconsistency on main thread");
                        continue;
                    }
                    // "Drag shadow dimensions must be positive"：文本选择的拖拽。长按落在
                    // 已选中的文字上时，framework 的 Editor.performLongClick 会走
                    // startDragAndDrop，拖影由 Editor.getTextThumbnailBuilder 现场 inflate 一个
                    // 纯 TextView、setText(选中段) 再 measure 得到；选区只剩换行 / 只剩宽度为 0
                    // 的 span（小说阅读器 ReaderTextBlockView 会往段落间插 '\n' 并挂
                    // ParagraphGapLineHeightSpan、LeadingMarginSpan）时量出来是 0，View
                    // .startDragAndDrop 直接抛 IllegalStateException。和上面的 Magnifier NPE
                    // 同源——都是 setTextIsSelectable(true) 才有的框架内部路径，而
                    // View.startDragAndDrop / startDrag 都是 final，子类没法拦，host app 也无法
                    // 阻止这个 measure 结果。丢掉这一次拖拽好过崩进程，选区本身还在。
                    // 全仓没有任何一处调用拖拽 API（grep startDragAndDrop / DragShadowBuilder
                    // 零命中），这句文案又是 AOSP 写死的，所以按 message 判定不会吞掉自家异常。
                    if (t instanceof IllegalStateException
                            && t.getMessage() != null
                            && t.getMessage().contains("Drag shadow dimensions must be positive")) {
                        Timber.w(t, "Suppressed text-selection drag shadow ISE on main thread");
                        continue;
                    }
                    // android.app.RemoteServiceException$CrashedByAdbException：adb 的
                    // `am crash <pkg>` 或某些 OEM 侧 shell-induced 信号会通过
                    // ActivityThread$H 投递。这条异常的 class 是 @hide，没法 instanceof，
                    // 用 message 文本判定（"shell-induced crash" 是 AOSP 写死的）。
                    // 不能拿 RemoteServiceException 类型粗筛 —— 它的兄弟类
                    // ForegroundServiceDidNotStartInTimeException 等是真 bug，必须照常崩。
                    if (t.getMessage() != null
                            && t.getMessage().contains("shell-induced crash")) {
                        Timber.w(t, "Suppressed shell-induced crash on main thread");
                        continue;
                    }
                    Thread.UncaughtExceptionHandler h =
                            Thread.getDefaultUncaughtExceptionHandler();
                    if (h != null) {
                        h.uncaughtException(Thread.currentThread(), t);
                    }
                    return;
                }
            }
        });

        //初始化context
        sContext = this;
        sGson = new Gson();
        //0.0127254

        // 正常路径下 attachBaseContext 的 prefetchPreferences 已经赋过值（且后台加载
        // 早就在跑了），这里只兜住它抛异常的情况。
        if (sPreferences == null) {
            sPreferences = getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        }

        Timber.plant(new Timber.DebugTree());

        initMMKV(this);
        networkStateManager = new NetworkStateManager(this);
        sSettings = Local.getSettings();

        // issue #865: 图片加速代理。在 mOkHttpClient 构建前把持久化的模式/自定义 host
        // 灌进 ImageHostManager —— requiresStandardClient() 靠它决定是否给图片客户端
        // 装直连覆盖，运行期 GlideUrlChild.rewrite 也读它。设置页改了只写 Settings，
        // 下次启动经此 hydrate 生效（图片客户端启动时一次性构建，跟直连开关同款限制）。
        ceui.lisa.http.ImageHostManager.INSTANCE.setModeOrdinal(sSettings.getImageHostMode());
        ceui.lisa.http.ImageHostManager.INSTANCE.setCustomHost(sSettings.getCustomImageHost());

        // 语言：迁旧字段 + 首启 fallback。必须在任何 UI 拉起前。
        ceui.pixiv.i18n.AppLocalesBootstrap.INSTANCE.bootstrap(sSettings);

        entityWrapper = new EntityWrapper(this);
        entityWrapper.initialize();

        // 进程级服务：构造都必须廉价（见 ServicesProvider 注释）。
        appLevelState = new AppLevelState();
        mangaTranslateModels = new ceui.pixiv.ui.translate.MangaTranslateModels(this);
        mangaBatchTranslateCenter = new ceui.pixiv.ui.translate.MangaBatchTranslateCenter(this, mangaTranslateModels);
        fanboxWebBridge = new ceui.loxia.FanboxWebBridge(this);
        queueDownloadManager = new ceui.pixiv.ui.bulk.QueueDownloadManager(this);
        pixivActionQueue = new ceui.pixiv.actions.PixivActionQueue(this);
        profileManager = new ceui.pixiv.db.discovery.ProfileManager(this);
        discoveryPool = new ceui.pixiv.db.discovery.DiscoveryPool(this, profileManager);
        accountOnlineReportOutbox = new ceui.pixiv.actions.AccountOnlineReportOutbox(this);
        nana7miSearchTelemetry = new ceui.pixiv.actions.Nana7miSearchTelemetry(this);
        remoteAppConfig = new ceui.pixiv.config.RemoteAppConfig(this);
        eventReporter = new ceui.pixiv.events.EventReporter(this);

        SessionManager.INSTANCE.initialize();

        // issue #931: 平板大屏双栏（Activity Embedding）。默认关闭，install 内先看
        // Settings.tabletSplitScreen 开关；打开后规则必须在任何 Activity
        // 拉起前注册好，冷启动首帧才是左 3/7 信息流 + 右 4/7 详情；手机（sw < 600dp）
        // 在 install 内直接跳过注册——挂上 organizer 会让手机回前台偶发卡 5 秒（#1002）。
        // 守卫理由同上面的 WorkManager：AE 要触碰 OEM 的 WM Extensions（HarmonyOS/EMUI
        // 这层出过 #853 类怪癖），一个纯可选的平板增强不配让全量用户启动崩溃——
        // 注册失败就退回没有分栏的老行为。
        try {
            ceui.pixiv.ui.embedding.TabletActivityEmbedding.INSTANCE.install(this);
        } catch (Throwable t) {
            Timber.w(t, "Activity Embedding rule install failed, tablet split disabled");
        }

        // 批量下载持久化队列（v33）：冷启动恢复 + 单并发消费循环。
        //
        // ⚠️ 不能挪进延迟批：init 内部在发现有 PENDING 行时会调
        // promptResumeOnFirstActivity，那东西是靠注册 ActivityLifecycleCallbacks 等
        // 「下一次 onActivityResumed」来弹恢复确认框的。放到 idle 里注册就晚于首个
        // Activity 的 onResume，恢复弹窗不会出现在首屏，而是等用户下次跳页/切回前台时
        // 突然冒出来。init 自身只有 ~3ms（同步部分只是赋 context + launch）。
        queueDownloadManager.start();

        // v38 illustId 索引列的一次性存量回填：把老下载记录的 illustId 补上，让
        // “这幅画下过没” 从 illustGson blob 全表 LIKE 扫描（2GB+ 卡）转成走索引。
        // 后台跑、跑完置标志、幂等；跑完前 hasDownloadRecord 用旧 LIKE 兜底。
        ceui.lisa.database.DownloadIdBackfill.runIfNeeded(this);

        // v41 page 列的一次性存量回填（issue #953）：从老记录的 fileName 反解出页码，
        // 让「已存在则跳过」和详情页复用本地文件能按 (illustId, page) 命中老记录，
        // 而不是只对之后新下载的生效。同样后台跑、跑完置标志、幂等。
        ceui.lisa.database.DownloadPageBackfill.runIfNeeded(this);

        // 服务端基础配置（pixshaft-api /v1/config）：目前只有借号搜索总开关，让它出问题时
        // 能不发版关掉。拉取全在后台，读到的永远是内存里的最后一个已知值。
        // 安全顺序：必须在 MMKV.initialize 之后（读缓存）、SessionManager.initialize 之后
        // （uid 是灰度分桶键）。
        remoteAppConfig.start();

        // 「屏蔽此作品」名单预热：判定跑在列表 bind 的热路径上（同步读内存 Set），名单本身来自
        // Room，这里提前读好，免得首屏第一次 bind 在主线程查库。顺带把老 MMKV 遮罩名单
        // 迁进 Room（一次性，见 MutedWorkStore）。warmUp 自己排到 store 的落库线程上跑，
        // 不用在这里另起一条。
        ceui.pixiv.ui.common.MutedWorkStores.warmUp();

        updateTheme();

        ThemeHelper.applyTheme(null, sSettings.getThemeType());

        // Toast 必须同步初始化：Common.showToast 遍布全 app，首帧路径上（未登录提示、
        // deep link 解析失败等）就可能弹，晚于它初始化就是一次没人看见的 toast。
        // 自身只有 ~2ms，不值得为它冒这个险。
        Toaster.init(this);
        int bottomOffset = BarUtils.getNavBarHeight() + (int) (48 * getResources().getDisplayMetrics().density);
        Toaster.setGravity(Gravity.BOTTOM, 0, bottomOffset);

        // :feeds 列表框架的宿主装配（主题配色 / 空态插画 / 错误文案 / Toast / 网络恢复）。
        // 必须在第一个列表页创建之前——晚了的话先建出来的页面会拿框架的 AOSP 默认配色。
        // 只是给一个 static 字段赋个无状态单例，成本可忽略，不进延迟批。
        // 排在 Toaster.init 之后：showMessage 直接转给 Toaster。
        ceui.pixiv.feeds.host.ShaftFeedHost.INSTANCE.install();

        // Activity 会直接读这个 static，必须在第一个 Activity 之前就位。三个空
        // ConcurrentHashMap，成本可忽略。

        // 「翻译整部」悬浮小窗(issue #925):每个 Activity 观察 app 级任务状态,有任务才 inflate。
        // 必须在第一个 Activity 创建前注册,否则首屏收不到。
        registerActivityLifecycleCallbacks(new ceui.pixiv.ui.translate.MangaBatchFloatInstaller());

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
                // 延迟批里 InAppBanners.bootstrap 要拿它补装 banner 宿主，见 runDeferredInit。
                currentActivity = activity;
                StringBuilder sb = new StringBuilder();
                sb.append("CREATE ").append(activity.getClass().getSimpleName());
                if (activity.getIntent() != null && activity.getIntent().getExtras() != null) {
                    Bundle extras = activity.getIntent().getExtras();
                    for (String key : extras.keySet()) {
                        Object val = extras.get(key);
                        sb.append("\n    ").append(key).append(" = ").append(val);
                    }
                }
                Timber.tag("ActivityTracker").d(sb.toString());
                // [DEBUG-568] recreated=true 表示这个 Activity 是被销毁后重建的（issue #568 复现关键标记）
                Timber.tag("DEBUG-568").w("CREATE %s@%s recreated=%s | %s",
                        activity.getClass().getSimpleName(),
                        Integer.toHexString(System.identityHashCode(activity)),
                        savedInstanceState != null,
                        memorySnapshot());
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {}

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                currentActivity = activity;
                Timber.tag("ActivityTracker").d("RESUME %s", activity.getClass().getSimpleName());
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {}

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                // [DEBUG-568] STOPPED 的后台 Activity 才是系统 releaseSomeActivities 的销毁候选
                Timber.tag("DEBUG-568").w("STOP %s@%s",
                        activity.getClass().getSimpleName(),
                        Integer.toHexString(System.identityHashCode(activity)));
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                // [DEBUG-568] 系统准备销毁该 Activity（或进程）前会先保存状态
                Timber.tag("DEBUG-568").w("SAVE_STATE %s@%s",
                        activity.getClass().getSimpleName(),
                        Integer.toHexString(System.identityHashCode(activity)));
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                if (currentActivity == activity) {
                    currentActivity = null;
                }
                Timber.tag("ActivityTracker").d("DESTROY %s", activity.getClass().getSimpleName());
                // [DEBUG-568] 三种销毁情形的区分（issue #568 的核心证据）：
                //   isFinishing=true                                  → 用户正常返回/关闭
                //   isFinishing=false + isChangingConfigurations=true → 配置变化（转屏等），ViewModel 存活，不会网络重载
                //   isFinishing=false + isChangingConfigurations=false→ ★系统主动销毁（内存压力），ViewModel 被清，
                //                                                        返回时必然触发网络重载 = issue #568 的症状
                boolean systemKilled = !activity.isFinishing() && !activity.isChangingConfigurations();
                Timber.tag("DEBUG-568").w("DESTROY %s@%s isFinishing=%s isChangingConfigurations=%s%s | %s",
                        activity.getClass().getSimpleName(),
                        Integer.toHexString(System.identityHashCode(activity)),
                        activity.isFinishing(),
                        activity.isChangingConfigurations(),
                        systemKilled ? " ★★★系统销毁了后台Activity(issue#568触发点)★★★" : "",
                        memorySnapshot());
            }
        });

        scheduleDeferredInit();
    }

    /**
     * 把「首帧不需要」的初始化排到主线程空闲之后。
     *
     * IdleHandler 在首帧画完、消息队列排空的那一刻回调，是「用户已经看到界面」和
     * 「还没有任何交互」之间的窗口，正好用来还这些债。兜底的 postDelayed 是防
     * 主线程一直不 idle（首帧带持续动画、或有人在狂投消息）：那种情况下遥测、
     * 聊天 WS 不能就此永远不启动。两条路都走 {@link #runDeferredInit()}，
     * 里头有幂等标志，谁先到算谁的。
     */
    private void scheduleDeferredInit() {
        Looper.myQueue().addIdleHandler(() -> {
            runDeferredInit();
            return false;   // 只跑一次
        });
        new Handler(Looper.getMainLooper())
                .postDelayed(this::runDeferredInit, DEFERRED_INIT_BACKSTOP_MS);
    }

    /**
     * 延迟批。只在主线程跑（IdleHandler / main Handler 都是），所以 {@link #deferredInitDone}
     * 不需要同步。
     *
     * <p><b>组内顺序仍然有意义</b>，和它们原先在 onCreate 里的相对顺序一致：
     * EventReporter 先于 PixivActionQueue（后者埋点）和 ShaftChatGateway
     * （签 WS URL 要 currentClientId）；ShaftChatGateway 先于 InAppBanners
     * （ChatBannerBridge 订阅 gateway.incoming）。
     *
     * <p>跨组的前置条件（MMKV、SessionManager）在 onCreate 里就已经同步做完，
     * 延迟到这里只会让它们更晚发生，不会更早。
     *
     * <p><b>每一步都过 {@link #step}</b>。原先这些直接写在 onCreate 里，谁抛异常谁把启动
     * 崩掉——响亮、必现、能被崩溃上报抓到。挪进 IdleHandler 之后性质变了：
     * {@code MessageQueue.next()} 自己把 {@code queueIdle()} 包在 try/catch 里，只留一条
     * {@code Log.wtf} 就继续跑（见 AOSP MessageQueue），进程不会崩。也就是说不接住的话，
     * 一次失败会静默吞掉它**后面所有**的初始化（聊天、banner、快捷方式、发现池……），
     * 而 {@link #deferredInitDone} 已经置位，兜底那条路也不会补跑——用户只会觉得
     * 「这些功能今天不见了」，日志里什么都没有。逐个隔离 + 打 error 日志，
     * 让失败既不扩散也不隐身。
     */
    private void step(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            Timber.e(t, "Deferred init step failed: %s", name);
        }
    }

    private void runDeferredInit() {
        if (deferredInitDone) {
            return;
        }
        deferredInitDone = true;

        // 旧 widget 删了但 WorkManager DB 里还残留它们的 PeriodicWork，
        // 系统会反复 ClassNotFoundException。一次性清理——置位后永不再跑，
        // 免得每次冷启都为一段历史遗留把 WorkManager（自带 Room 库 + 线程池）唤醒。
        try {
            if (!getMMKV().decodeBool(LEGACY_WIDGET_WORK_CLEARED_KEY, false)) {
                androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(this);
                wm.cancelUniqueWork("illust_grid_widget_work");
                wm.cancelUniqueWork("illust_grid_widget_work_once");
                getMMKV().encode(LEGACY_WIDGET_WORK_CLEARED_KEY, true);
            }
        } catch (Throwable t) {
            Timber.w(t, "Failed to cancel legacy widget work");
        }

        // 动图 RIFE 补帧的中间帧目录(cache/rife_work_*)残留清扫。正常路径由播放引擎的
        // finally 兜底删除，但补帧是分钟级满载 GPU，正是最容易被系统杀后台的窗口，被杀就
        // 留下几百 MB 中间 PNG 且没有任何东西会去收。后台跑、失败静默。
        step("UgoiraEngine.sweep", () -> ceui.pixiv.ui.bulk.UgoiraEngine.sweepStaleRifeWork(this));

        // 社区榜单事件上报（shaft-api-v2）。完全 fire-and-forget，失败静默，
        // 任何崩溃都被它自己捕获。安全顺序：必须在 MMKV.initialize 之后。
        step("EventReporter", eventReporter::start);

        // 收藏/关注的持久化限流队列。这类写操作原本是点一次发一次，连点或批量操作
        // 很容易被 pixiv 429；现在统一排队串行发送，撞限流整队冷却并自动重试，
        // 进程被杀后下次启动继续把没发完的发出去。
        // 安全顺序：必须在 SessionManager.initialize 之后（gate 读登录态）、
        // EventReporter.start 之后（PixivActions 会埋点）。
        step("PixivActionQueue", pixivActionQueue::start);

        // Nana7mi 搜索遥测使用独立的 ActionQueue 数据库和消费循环：同样具备落盘/重试，
        // 但服务端或遥测自身故障绝不能拖慢收藏、关注等用户业务动作。
        if (!ceui.lisa.BuildConfig.IS_LITE) {
            step("Nana7miSearchTelemetry", nana7miSearchTelemetry::start);
        }

        // AccountResponse 上报使用独立的全局 outbox：它不属于当前登录用户，切账号或
        // 登出后也必须继续补报刚 refresh 出来的新 token。
        if (!ceui.lisa.BuildConfig.IS_LITE) {
            step("AccountOnlineReportOutbox", accountOnlineReportOutbox::start);
        }

        // shaft-api-v2 chat WebSocket gateway. App-scoped — 一个 WebSocketManager
        // 全局复用,生命周期与进程一致(匿名协议没有"退登")。必须在
        // EventReporter.start 之后,因为 ShaftHmacAuthProvider 要靠
        // currentClientId() 签 URL,init 同步把 clientId 写好。
        step("ShaftChatGateway", () -> ceui.pixiv.chat.api.ShaftChatGateway.INSTANCE.bootstrap(this));

        // In-app banner system. 必须在 ShaftChatGateway.bootstrap 之后,
        // ChatBannerBridge 订阅 gateway.incoming。
        // 传 currentActivity：它注册的两个 lifecycle callback 现在才挂上，首个 Activity
        // 的 created/resumed 早过去了，不补这一下首屏整场都没有 banner 宿主。
        step("InAppBanners", () -> ceui.pixiv.banner.InAppBanners.INSTANCE.bootstrap(this, currentActivity));

        // 网络变化时停掉下载队列。CONNECTIVITY_ACTION 只与下载有关，首帧不需要。
        step("NetWorkStateReceiver", () -> {
            if (netWorkStateReceiver == null) {
                netWorkStateReceiver = new NetWorkStateReceiver();
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(netWorkStateReceiver, filter);
        });

        try {
            FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(
                    sSettings.isFirebaseEnable()
            );
        } catch (Exception e) {
            Timber.w(e, "Failed to initialize Firebase Analytics");
        }

        // 动态快捷方式：getDynamicShortcuts 是一次 binder IPC，O+ 还要现画一张
        // adaptive icon 位图。只有用户长按启动图标才看得到，没有任何理由抢首帧。
        step("ShortcutHelper", ShortcutHelper::addAppShortcuts);

        // 初始化发现池 + 异步构建用户画像
        step("DiscoveryPool", () -> {
            Timber.d("Discovery/Init >>> initializing DiscoveryPool");
            discoveryPool.initialize();
            Timber.d("Discovery/Init >>> starting ProfileManager.buildProfile on background thread");
            new Thread(() -> {
                try {
                    profileManager.buildProfile();
                    Timber.d("Discovery/Init <<< ProfileManager.buildProfile completed");
                } catch (Exception e) {
                    Timber.e(e, "Discovery/Init <<< ProfileManager.buildProfile FAILED");
                }
            }).start();
        });

        // 冷启动后拉一次 /v1/user/me/state（账号状态）。纯 fire-and-forget：只发一次、
        // IO 线程、内部四重防线吞掉一切异常（见 SelfProfileWarmup）。安全顺序：必须在
        // SessionManager.initialize 之后（onCreate 已同步做完），否则读不到登录态白发一次。
        step("SelfProfileWarmup", ceui.pixiv.session.SelfProfileWarmup::trigger);

        // 同义词词典内置数据自动导入（issue #904）：启动 15 秒后后台静默导入，只导一次
        // （flag 记 MMKV 设备本地，不随 Settings 同步）。合并导入不覆盖用户已有词典。
        // 一次性去重清理（issue #905）：已导入旧版冗余词典的设备，清掉大小写重复/与目标同名的同义词。
        // 双重前置条件：功能总开关打开（默认关闭，普通用户无感知）且本设备有待办（未导入/未清理）——
        // 两个 flag 都已置位的设备不排任务不起线程，保持零开销。
        step("SynonymBuiltinDict", () -> {
            if (sSettings.isSynonymDictEnabled()
                    && (!ceui.pixiv.ui.synonym.SynonymBuiltinDict.isImported()
                            || !ceui.pixiv.ui.synonym.SynonymBuiltinDict.isDeduped())) {
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        new Thread(() -> {
                            // 先导入后去重：保证旧设备「导入冗余版 → 清理」一次启动内完成
                            ceui.pixiv.ui.synonym.SynonymBuiltinDict.autoImportIfNeeded(this);
                            ceui.pixiv.ui.synonym.SynonymBuiltinDict.dedupeIfNeeded(this);
                        }).start(), 15_000);
            }
        });
    }

    /**
     * [DEBUG-568] 内存快照：Java 堆 + native 堆 + 系统可用内存。
     * 用于把"系统销毁后台 Activity"和"内存压力"在时间线上对齐。
     */
    private static String memorySnapshot() {
        try {
            Runtime rt = Runtime.getRuntime();
            long javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
            long javaMaxMb = rt.maxMemory() / 1024 / 1024;
            long nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024;
            android.app.ActivityManager am = (android.app.ActivityManager)
                    sContext.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return "mem[java=" + javaUsedMb + "/" + javaMaxMb + "MB native=" + nativeMb
                    + "MB sysAvail=" + (mi.availMem / 1024 / 1024) + "MB lowMemory=" + mi.lowMemory + "]";
        } catch (Throwable t) {
            return "mem[unavailable]";
        }
    }

    /**
     * [DEBUG-568] 把 trim level 翻译成可读名称。
     * RUNNING_CRITICAL(15) 是关键：收到它说明系统内存极度紧张，
     * framework 会顺带销毁本进程的后台 Activity（= issue #568 的触发器）。
     */
    private static String trimLevelName(int level) {
        switch (level) {
            case TRIM_MEMORY_RUNNING_MODERATE: return "RUNNING_MODERATE(5)";
            case TRIM_MEMORY_RUNNING_LOW: return "RUNNING_LOW(10)";
            case TRIM_MEMORY_RUNNING_CRITICAL: return "RUNNING_CRITICAL(15)";
            case TRIM_MEMORY_UI_HIDDEN: return "UI_HIDDEN(20)";
            case TRIM_MEMORY_BACKGROUND: return "BACKGROUND(40)";
            case TRIM_MEMORY_MODERATE: return "MODERATE(60)";
            case TRIM_MEMORY_COMPLETE: return "COMPLETE(80)";
            default: return "UNKNOWN(" + level + ")";
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // [DEBUG-568] 内存压力回调时间线
        Timber.tag("DEBUG-568").w("onTrimMemory %s | %s", trimLevelName(level), memorySnapshot());
        // 字段在 onCreate 里构造；onCreate 中途抛异常时系统仍可能回调这里，判空兜底。
        if (level >= TRIM_MEMORY_BACKGROUND) {
            if (fanboxWebBridge != null) fanboxWebBridge.release();
            if (mangaBatchTranslateCenter != null) mangaBatchTranslateCenter.releaseModelsIfIdle();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        // [DEBUG-568]
        Timber.tag("DEBUG-568").w("onLowMemory | %s", memorySnapshot());
    }

    /**
     * 图片 / 下载共用的 OkHttp 客户端，首次取用时才构建。
     *
     * <p>两个调用方都不在启动关键路径上：{@code GlideConfiguration.registerComponents}
     * 由 Glide 首次加载图片时触发，{@code Manager} 只在有下载任务时派生。而构建本身在
     * 直连模式下要新建 {@code SSLContext}（拉起 Conscrypt provider），放在 onCreate
     * 里等于让所有用户替「可能永远不加载图片的那一帧」买单。
     *
     * <p>synchronized：Glide 的 registry 初始化不保证在主线程，两个调用方可能并发首次
     * 触发，构建两个 client 会让连接池 / ProgressManager 的拦截器各存一份。
     */
    public synchronized OkHttpClient getOkHttpClient() {
        if (mOkHttpClient == null) {
            mOkHttpClient = buildOkHttpClient();
        }
        return mOkHttpClient;
    }

    private OkHttpClient buildOkHttpClient() {
        // 退回 H1.1 后同款 AIOOBE 仍在线上复现（栈里已经是 Http1ExchangeCodec.writeRequest），
        // 说明成因不是 H2 帧缓冲，而是一条连接被两个 exchange 同时拿去写请求头。okhttp 连接池
        // 那层改不动，这里兜住崩溃形态：把非 IOException 包成 IOException，让 okhttp 拆掉坏连接、
        // 走 onFailure 而不是从 dispatcher 线程 uncaught 崩进程。详见该类注释。
        // 必须是 network interceptor，且要挂在最外层才能连 ProgressManager 自己的 body 包装一起盖住
        // ——ProgressManager.with() 内部就是 addNetworkInterceptor，所以得先加自己再交给它。
        // 下载(Manager)、ugoira 由本 client newBuilder() 派生，自动继承；聊天 WebSocket 是独立 client，不在内。
        OkHttpClient.Builder glideBuilder = ProgressManager.getInstance().with(
                new OkHttpClient.Builder()
                        .addNetworkInterceptor(new ceui.lisa.http.BufferCorruptionGuardInterceptor()));
        // 图片客户端一律强制 HTTP/1.1。Glide 加载缩略图网格会在单条 H2 连接上并发开大量
        // stream，okhttp 的 Http2Writer 共享帧缓冲在这种高并发下会被写坏，抛出
        // ArrayIndexOutOfBoundsException(okio checkOffsetAndCount / AsyncTimeout.write)导致崩溃。
        // 下载(Manager)、ugoira 早已各自退回 H1.1，这里统一在源头兜住，非直连模式同样生效。
        glideBuilder.protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1));
        // issue #865: 直连覆盖(HttpDns 硬编码 210.140.139.x + 无 SNI 的 TLS)只对
        // 原始 i.pximg.net 有效，会打死 pixiv.cat / 自定义反代。所以非 PIXIV 模式下
        // 图片客户端退回系统 DNS + 标准 TLS。API 客户端(Retro/Client 的 Cronet 直连)
        // 与本客户端相互独立，不受影响。
        if (sSettings.isDirectConnect()
                && !ceui.lisa.http.ImageHostManager.INSTANCE.requiresStandardClient()) {
            // 图片走 https://i.pximg.net 原始 URL，在 OkHttp 层面：
            // 1. 自定义 DNS 绕过 DNS 污染
            // 2. 无 SNI 的 TLS 绕过 GFW（图片服务器不要求 SNI）
            // 3. HTTP/1.1 已在上面统一强制（既避免 H2 写坏崩溃，也避免 H2 复用连接被 GFW 整体干扰）
            try {
                ceui.lisa.http.TrustAllCertManager trustManager = new ceui.lisa.http.TrustAllCertManager();
                glideBuilder.sslSocketFactory(new ceui.lisa.http.RubySSLSocketFactory(), trustManager);
                glideBuilder.hostnameVerifier((hostname, session) -> true);
            } catch (Exception e) {
                Timber.e(e, "Direct-connect SSL init error");
            }
            glideBuilder.dns(ceui.lisa.http.HttpDns.getInstance());
            glideBuilder.connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS);
            glideBuilder.readTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        return glideBuilder.build();
    }

    /**
     * Update the theme according to the setting.
     * */
    private void updateTheme() {
        int current = Shaft.sSettings.getThemeIndex();
        // 自定义主题色（issue #1014）：先把 @color/custom_theme_primary 换成用户的色值，再
        // setTheme —— theme attr 是 setTheme 那一刻解析的，顺序反了就拿到占位色。
        // 系统不支持（< Android 11）或存的色值非法时 isActive() 为 false，索引 -1 落进下面
        // switch 的 default，回落 0 号预设。
        if (ceui.pixiv.ui.settings.CustomThemeColor.isActive()) {
            ceui.pixiv.ui.settings.CustomThemeColor.applyResourceOverride(this);
            setTheme(R.style.AppTheme_Custom);
            return;
        }
        switch (current) {
            case 0:
                setTheme(R.style.AppTheme_Index0);
                break;
            case 1:
                setTheme(R.style.AppTheme_Index1);
                break;
            case 2:
                setTheme(R.style.AppTheme_Index2);
                break;
            case 3:
                setTheme(R.style.AppTheme_Index3);
                break;
            case 4:
                setTheme(R.style.AppTheme_Index4);
                break;
            case 5:
                setTheme(R.style.AppTheme_Index5);
                break;
            case 6:
                setTheme(R.style.AppTheme_Index6);
                break;
            case 7:
                setTheme(R.style.AppTheme_Index7);
                break;
            case 8:
                setTheme(R.style.AppTheme_Index8);
                break;
            case 9:
                setTheme(R.style.AppTheme_Index9);
                break;
            default:
                setTheme(R.style.AppTheme_Default);
                break;
        }
    }

    /**
     * 当前主题色的 #RRGGBB。色值收口在 {@link ceui.pixiv.ui.settings.ThemeColorCatalog}——
     * 「主题色彩」列表页读的是同一份，以前这里的 switch 是第二份硬编码，已经和列表页漂了
     * （6 号一处 #F44336 一处 #f44336，Color.parseColor 大小写不敏感所以没人发现）。
     * 越界回落 0 号，与 updateTheme 的 default 分支一致。
     *
     * <p>自定义档（issue #1014）在预设目录之外，所以从
     * {@link ceui.pixiv.ui.settings.CustomThemeColor#currentHex()} 出——它自己负责「自定义没
     * 生效就回落预设目录」，两条路的回落规则保持同义。
     */
    public static String getThemeColor() {
        return ceui.pixiv.ui.settings.CustomThemeColor.currentHex();
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        try {
            super.unbindService(conn);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static MMKV getMMKV() {
        if (mmkv == null) {
            mmkv = MMKV.defaultMMKV();
        }
        return mmkv;
    }

    @Override
    public @NotNull MMKV getPrefStore() {
        return getMMKV();
    }

    @Override
    public @NotNull NetworkStateManager getNetworkStateManager() {
        return networkStateManager;
    }

    @Override
    public @NotNull EntityWrapper getEntityWrapper() {
        return entityWrapper;
    }

    @Override
    public @NotNull AppLevelState getAppLevelState() {
        return appLevelState;
    }

    @Override
    public @NotNull ceui.pixiv.ui.translate.MangaTranslateModels getMangaTranslateModels() {
        return mangaTranslateModels;
    }

    @Override
    public @NotNull ceui.pixiv.ui.translate.MangaBatchTranslateCenter getMangaBatchTranslateCenter() {
        return mangaBatchTranslateCenter;
    }

    @Override
    public @NotNull ceui.loxia.FanboxWebBridge getFanboxWebBridge() {
        return fanboxWebBridge;
    }

    @Override
    public @NotNull ceui.pixiv.ui.bulk.QueueDownloadManager getQueueDownloadManager() {
        return queueDownloadManager;
    }

    @Override
    public @NotNull ceui.pixiv.actions.PixivActionQueue getPixivActionQueue() {
        return pixivActionQueue;
    }

    @Override
    public @NotNull ceui.pixiv.db.discovery.ProfileManager getProfileManager() {
        return profileManager;
    }

    @Override
    public @NotNull ceui.pixiv.db.discovery.DiscoveryPool getDiscoveryPool() {
        return discoveryPool;
    }

    @Override
    public @NotNull ceui.pixiv.actions.AccountOnlineReportOutbox getAccountOnlineReportOutbox() {
        return accountOnlineReportOutbox;
    }

    @Override
    public @NotNull ceui.pixiv.actions.Nana7miSearchTelemetry getNana7miSearchTelemetry() {
        return nana7miSearchTelemetry;
    }

    @Override
    public @NotNull ceui.pixiv.config.RemoteAppConfig getRemoteAppConfig() {
        return remoteAppConfig;
    }

    @Override
    public @NotNull ceui.pixiv.events.EventReporter getEventReporter() {
        return eventReporter;
    }
}
