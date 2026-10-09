package ceui.lisa.utils;

import android.text.TextUtils;

import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.PathUtils;
import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.http.ImageReadTimeout;
import ceui.pixiv.cache.ImageCacheQuota;
import ceui.pixiv.download.toast.DownloadToastKind;
import ceui.pixiv.snapshot.AutoSnapshotQuota;
/**
 * A class about all the application settings.
 * */
public class Settings {

    //只包含1P图片的下载路径
    public static final String FILE_PATH_SINGLE = PathUtils.getExternalPicturesPath() + "/ShaftImages";
    public static final String FILE_PATH_NOVEL = PathUtils.getExternalDownloadsPath() + "/ShaftNovels";
    public static final String FILE_PATH_SINGLE_R18 = PathUtils.getExternalPicturesPath() + "/ShaftImages-R18";

    //下载的GIF 压缩包存放在这里
    public static final String FILE_GIF_PATH = PathUtils.getExternalDownloadsPath();

    //log日志，
    public static final String FILE_LOG_PATH = PathUtils.getExternalDownloadsPath() + "/ShaftFiles";

    //下载的GIF 压缩包解压之后的结果存放在这里
    public static final String FILE_GIF_CHILD_PATH = PathUtils.getExternalAppCachePath();

    //已制作好的GIF存放在这里
    public static final String FILE_GIF_RESULT_PATH = PathUtils.getExternalPicturesPath() + "/ShaftGIFs";

    //WEB下载
    public static final String WEB_DOWNLOAD_PATH = PathUtils.getExternalPicturesPath() + "/ShaftWeb";

    public static final String FILE_PATH_BACKUP = PathUtils.getExternalDownloadsPath() + "/ShaftBackups";

    private int themeIndex;

    private int lineCount = 2;

    private boolean useStaggeredLayout = true;

    /** 插画列表布局，取值见 {@link ceui.pixiv.ui.common.IllustListLayout}（序号，0 = 瀑布流）。 */
    private int illustListLayout = 0;

    /** 各 uid 在本设备最近一次已应用的 moonAPI 版本号。key 是 uid.toString()。 */
    private Map<String, Integer> moonAppliedVersions = new HashMap<>();

    public Map<String, Integer> getMoonAppliedVersions() {
        if (moonAppliedVersions == null) {
            moonAppliedVersions = new HashMap<>();
        }
        return moonAppliedVersions;
    }

    public void setMoonAppliedVersions(Map<String, Integer> moonAppliedVersions) {
        this.moonAppliedVersions = moonAppliedVersions;
    }

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int lineCount) {
        this.lineCount = lineCount;
    }

    public boolean isUseStaggeredLayout() {
        return useStaggeredLayout;
    }

    public void setUseStaggeredLayout(boolean useStaggeredLayout) {
        this.useStaggeredLayout = useStaggeredLayout;
    }

    public int getIllustListLayout() {
        return illustListLayout;
    }

    public void setIllustListLayout(int illustListLayout) {
        this.illustListLayout = illustListLayout;
    }

    public int getThemeIndex() {
        return themeIndex;
    }

    public void setThemeIndex(int themeIndex) {
        this.themeIndex = themeIndex;
    }

    /**
     * 自定义主题色的 {@code #RRGGBB}（issue #1014）。只在
     * {@code themeIndex == }{@link ceui.pixiv.ui.settings.CustomThemeColor#INDEX} 时被读；
     * 没设过是 null，解析一律走 {@link ceui.pixiv.ui.settings.CustomThemeColor#normalize}。
     */
    private String customThemeColor;

    public String getCustomThemeColor() {
        return customThemeColor;
    }

    public void setCustomThemeColor(String customThemeColor) {
        this.customThemeColor = customThemeColor;
    }

    // ===== 标签译文颜色（#1047-5）=====
    /** 跟随主题（默认）：译文与标签原文同色。 */
    public static final int TAG_TRANSLATION_COLOR_FOLLOW_THEME = -2;

    /**
     * 标签译文颜色：-2 = 跟随主题；0..9 = 主题色目录预设；
     * {@link ceui.pixiv.ui.settings.CustomThemeColor#INDEX} = 自定义色。
     * 老配置没有该字段时按跟随主题处理（getter 兜底）。
     */
    private Integer tagTranslationColorIndex;

    /** 标签译文自定义色的 #RRGGBB（仅 tagTranslationColorIndex 为自定义档时读取）。 */
    private String tagTranslationColorCustomHex;

    public int getTagTranslationColorIndex() {
        return tagTranslationColorIndex != null
                ? tagTranslationColorIndex
                : TAG_TRANSLATION_COLOR_FOLLOW_THEME;
    }

    public boolean isTagTranslationColorFollowTheme() {
        return getTagTranslationColorIndex() == TAG_TRANSLATION_COLOR_FOLLOW_THEME;
    }

    public void setTagTranslationColorFollowTheme() {
        this.tagTranslationColorIndex = TAG_TRANSLATION_COLOR_FOLLOW_THEME;
    }

    public void setTagTranslationColorIndex(int tagTranslationColorIndex) {
        this.tagTranslationColorIndex = tagTranslationColorIndex;
    }

    public String getTagTranslationColorCustomHex() {
        return tagTranslationColorCustomHex;
    }

    public void setTagTranslationColorCustomHex(String tagTranslationColorCustomHex) {
        this.tagTranslationColorCustomHex = tagTranslationColorCustomHex;
    }

    //主页显示R18
    private boolean mainViewR18 = false;

    //是否启用 FIREBASE_ANALYTICS_COLLECTION
    private boolean isFirebaseEnable = true;

    //是否启用 Timber 日志写入「日志文件」桶（试验性，重启生效）
    private boolean logFileEnabled = false;

    //哪些 Activity 改用传统返回（不用系统预测动画）。存**类名**的集合。
    //PredictiveBackSuppressor 读取它：命中的 Activity 会挂一个「常开」的
    //OnBackPressedCallback，AndroidX 随即向系统注册 OnBackInvokedCallback，
    //系统便放弃自己的预测动画——用来绕开部分系统预测动画的坏适配
    //（如 OriginOS 5 把侧边栏展开态误当底页，返回时左半屏闪烁）。
    //默认**空集合** = 全部维持预测返回。老用户 JSON 里没有这个字段，Gson 走无参构造，
    //初始值就是空集合，行为与升级前完全一致。
    private LinkedHashSet<String> predictiveBackDisabledActivities = new LinkedHashSet<>();

    //侧边栏（抽屉）的预测式返回跟手动画是**应用自绘**的（见 DrawerPredictiveBack），
    //不属于系统预测动画，所以单独一个开关。关闭后抽屉不再跟手，直接走 DrawerLayout
    //自带的关闭动画。默认 true（= 维持现状）。
    private boolean drawerPredictiveBackEnabled = true;

    //[实验] 尝试抑制「进入任意页后返回」时的闪烁,默认关。
    //打开后 PredictiveBackSuppressor 会把所有 Activity 纳入,侧边栏行点击后紧接着创建的
    //那个 Activity 整条生命周期保持回调 enabled,系统便不播预测动画(闪烁随动画出现)。
    //在 OriginOS 5 上实测有效;其它 ROM 上属于未验证行为,所以默认关。
    private boolean suppressBackFlickerAnyPage = false;

    private long currentProgress = 0L;

    public long getCurrentProgress() {
        return currentProgress;
    }

    public void setCurrentProgress(long currentProgress) {
        this.currentProgress = currentProgress;
    }

    private boolean trendsForPrivate = false;

    //浏览历史List点击动画
    private boolean viewHistoryAnimate = true;

    //设置页面进场动画
    private boolean settingsAnimate = true;

    //动态页不显示已收藏的插画、漫画和小说，默认不屏蔽
    private boolean deleteStarIllust = false;

    //排行榜过滤已收藏的作品，默认过滤
    private boolean filterRankBookmarked = true;

    //搜索页过滤已收藏的作品，默认不过滤。
    //入口在设置页「过滤已收藏」弹窗与搜索筛选「其他条件」，过滤在搜索数据源建条目时现读。
    private boolean searchFilterBookmarked = false;

    //屏蔽，不显示AI创作的作品，默认不屏蔽
    private boolean deleteAIIllust = false;

    //屏蔽 AI 时的强度：0=完全不显示（默认），1=模糊粒子化
    private int aiBlockStrength = 0;

    //屏蔽 AI 时的豁免作者 ID（保持添加顺序、自动去重）
    private LinkedHashSet<Long> aiBlockExemptAuthorIds = new LinkedHashSet<>();

    //是否开启直连模式，true 开启  false 自行代理
    @SerializedName("autoFuckChina")
    private boolean directConnect = false;

    //是否启用 DoH（安全 DNS）解析。关闭时直接走系统 DNS / 内置兜底 IP，
    //适合本地已是可信 DNS 的场景。issue #616
    //默认 true，与历史行为保持一致（升级用户不会被静默关闭 DoH）
    private boolean useSecureDns = true;

    private boolean relatedIllustNoLimit = true;

    //图片加速代理（issue #865）。imageHostMode: 0=Pixiv 官方 1=pixiv.cat 2=pixiv.re 3=pixiv.nl 4=自定义反代；
    //customImageHost: 自定义反代地址前缀（如 https://your.proxy）。旧的 usePixivCat 布尔从未接线，已移除。
    private int imageHostMode = 0;
    private String customImageHost = "";

    //App API 代理（PxveAPI 风格）。appApiProxy: 代理根地址（需 https:// 前缀）；**地址非空即启用**，
    //空 = 不代理（设置页独立输入选项，与直连模式可共存）。请求改写为
    //https://<appApiProxy>/pixiv-app-api/* 与 /pixiv-oauth/*。
    //与直连模式（directConnect）**可共存**：代理拦截器挂在 Cronet 之前，只改写 app-api/oauth 域名，
    //其余请求原样放行给直连，二者互不干扰。
    private String appApiProxy = "";

    //GitHub 加速地址（gh-proxy 风格）。githubProxy: 加速站根地址（https://host[/path]；空 = 不使用）。
    //使用方式就是在 https://*.github.com 资源的 https:// 之前插入它，见 ceui.lisa.http.GithubProxy。
    //只影响「从 GitHub 拉取」的那几条链路（检查更新 / 下载 APK / AI 模型 / 表情包资源），
    //Pixiv、主 API（pixshaft.com）、图片反代一概不经过它，所以可以随时改、不用重启。
    private String githubProxy = "";

    //缩略图图片显示大图
    private boolean showLargeThumbnailImage = false;

    //一级详情FragmentIllust 图片显示原图
    private boolean showOriginalPreviewImage = false;


    //是否显示开屏 dialog
    private boolean showPixivDialog = true;

    //默认私人收藏
    private boolean privateStar = false;

    //默认私人关注（长按关注的语义保持不变，只改短按的默认可见性）
    private boolean privateFollow = false;

    //列表页面是否显示收藏按钮
    private boolean showLikeButton = true;

    //收藏按钮振动反馈，默认开启
    private boolean likeHapticEnable = true;

    //列表滑到边缘振动反馈（#1193），默认开启
    private boolean scrollEdgeHapticEnable = true;

    //插画/漫画瀑布流卡片上是否显示收藏按钮，默认显示
    private boolean showIllustCardBookmarkButton = true;

    //小说卡片是否显示标签
    private boolean showNovelCardTags = true;

    //小说列表卡片是否显示标签译文，默认关闭以保持列表紧凑
    private boolean showNovelCardTagTranslations = false;

    //小说列表卡片标签是否折叠（超过 6 个换成「+N」），默认折叠
    private boolean collapseNovelCardTags = true;

    //直接下载单个作品所有P
    private boolean directDownloadAllImage = true;

    // 下载 JPEG 时把作品标签写进 XMP dc:subject(相册/图管软件读的「关键词」字段)。默认关:
    // 每张多一次全文件重写,只有显式开启才付出这次 IO(issue #938)。
    private boolean writeTagsToImageExif = false;

    // 低调下载:下载完成后把文件时间戳回拨到很早以前,不出现在相册及微信 / QQ
    // 选图列表的「最近」前排(issue #731)。默认关。
    private boolean silentDownload = false;

    private boolean saveViewHistory = true;

    // 浏览记录云同步(pixshaft-api)。默认开启,但首次会弹一次同意框让用户选择是否关闭。
    private boolean cloudHistorySync = true;
    // 同意框是否已经弹过(每台设备一次)。
    private boolean cloudHistoryConsentShown = false;
    // 存量本地历史回填(#989)的完成标记,每设备一次:0 = 未回填,非 0 = 已回填(值为当时的
    // 登录 uid,仅作记录)。关云同步或导入历史备份时清零重跑。见 HistoryBackfill 类注释。
    private long cloudHistoryBackfillDoneUid = 0L;

    private boolean r18DivideSave = false;

    //AI作品下载至单独的目录
    private boolean AIDivideSave = false;


    //在我的收藏列表，隐藏收藏按钮，默认显示
    private boolean hideStarButtonAtMyCollection = false;

    //按标签收藏时全选标签。默认不全选
    private boolean starWithTagSelectAll = false;

    //单P作品的文件名是否带P0
    private boolean hasP0 = false;

    //作品详情使用V3沉浸式页面
    private boolean useArtworkV3 = false;

    //小说列表点击 item 直接进 V3 正文（略过详情页），默认关闭
    private boolean novelListDirectToReader = false;

    //详情页「作品详情」(插画/漫画 V3)与「作品档案」(小说)面板默认折叠（#1044），默认展开
    private boolean detailPanelCollapsedByDefault = false;

    /**
     * 小说列表自动屏蔽（issue #743）。三个阈值都是 0 = 关闭，只作用于小说列表，插画/漫画不受影响。
     * 判定见 {@link ceui.lisa.helper.IllustNovelFilter#judgeNovelSpam}。
     */
    //正文字数低于该值的小说被屏蔽（0 = 不限）
    private int novelFilterMinTextLength = 0;

    //正文字数高于该值的小说被屏蔽（0 = 不限）
    private int novelFilterMaxTextLength = 0;

    //任意一个标签名长度超过该值的小说被屏蔽（0 = 不限）——刷广告的常把整句话塞进 tag 名
    private int novelFilterMaxTagNameLength = 0;

    private String illustPath = "";

    private String novelPath = "";

    private String gifResultPath = "";

    private String gifZipPath = "";

    private String gifUnzipPath = "";

    private String webDownloadPath = "";

    private int novelHolderColor = 0;

    private int novelHolderTextColor = 0;

    private int novelHolderTextSize = 16;

    private int bottomBarOrder = 0;

    private boolean reverseDialogNeverShowAgain = false;

    private String appLanguage = "";

    private String fileNameJson = "";

    private String rootPathUri = "";

    private int downloadWay = 0; //0传统模式，保存到Pictures目录下。    1 SAF模式保存到自选目录下

    private boolean filterComment = false; // 过滤垃圾评论，默认不开启

    private int transformerType = 5; // 二级详情转场动画，默认是3D盒子

    private boolean showRelatedWhenStar = true; // 收藏作品时展示关联作品


    private boolean illustLongPressDownload = false; // 插画详情长按下载

    private int saveForSeparateAuthorStatus = 0; // 不同作者单独保存

    private boolean autoPostLikeWhenDownload = false; // 下载时自动收藏

    private boolean autoFollowAfterStar = false; // 收藏后自动关注作者

    private boolean autoDownloadAfterStar = false; // 收藏后自动下载

    private volatile boolean autoSnapshotOnBookmark = false; // 试验性：收藏时生成离线快照

    private volatile boolean autoSnapshotOnIllustManga = false; // 试验性：插画/漫画自动生成快照

    /** 试验性：自动快照总大小上限（MB）；默认等于旧硬编码 200 MB。 */
    private volatile int autoSnapshotMaxMb = AutoSnapshotQuota.DEFAULT_LIMIT_MB;

    /** 图片缓存「预期上限」（MB）；只在 Glide 初始化时生效，改完需重启 App。默认 = Glide 原生 250 MB。 */
    private volatile int imageCacheMaxMb = ImageCacheQuota.DEFAULT_LIMIT_MB;

    /**
     * 图片「加载」与「下载」共用的读超时（秒）。分直连 / 非直连两种模式，各自默认 = 该模式滑动条
     * 上界（非直连 10s / 直连 30s，见 ImageReadTimeout）。只在共享 OkHttp client 构建时读一次
     * （Shaft.buildOkHttpClient），改完需重启 App；切换直连开关时会重置为对应模式的默认值。
     */
    private volatile int imageReadTimeoutSeconds = ImageReadTimeout.UNSET_SECONDS;

    /**
     * 「图片加载也容许一次断流超时并静默重试」。默认关。
     *
     * 与下载侧的读超时静默重连（{@code Manager.shouldSilentlyRetryAfterReadTimeout}）同源同口径
     * （都用 {@code NetworkFailureClassifier.isReadTimeoutFailure}，每条只给一次），但**不受阈值是否为
     * 默认值影响** —— 下载侧是「调小阈值即自动生效」，图片侧是用户显式勾选，勾了就该生效。
     *
     * 值在每次失败判定时现取（{@code ImageLoadTask}），所以改完**不用重启 App**（与上面的读超时不同）。
     */
    private volatile boolean imageLoadRetryOnStall = false;

    private boolean r18FilterDefaultEnable = false; // 默认开启R18内容过滤

    /**
     * 旧字段：下载结果提示总开关。已被「下载相关提示消息」的逐项开关（{@link DownloadToastKind}）
     * 取代，只剩 {@link #migrateLegacyDownloadToasts(Settings)} 还在读它做旧升新推导，
     * 并回填供降级安装使用。
     */
    private boolean toastDownloadResult = true;

    /**
     * 「下载相关提示消息」里被安静掉的消息类型，存 {@link DownloadToastKind#name()}。
     * 空集 = 全部提示（默认；新装、以及以后新增的消息类型天然都是这个值）。
     */
    private LinkedHashSet<String> mutedDownloadToasts = new LinkedHashSet<>();

    /**
     * 「旧总开关 → 逐项开关」这件事是否已经做过一次。落盘记着，否则用户在新版里把全部提示
     * 重新打开之后（那时旧开关又被反向回填成 false），下一次装载会按旧开关把当年那几条重新
     * 种成安静；反过来，旧版 JSON / 旧备份里没有这个标记，迁移才会照旧开关推导一次。
     */
    private boolean downloadToastsMigrated = false;

    private boolean autoExportIllustCaption = false; // 插画/漫画下载时自动导出简介，默认关

    private int autoExportCaptionMinLength = 1; // 简介自动导出需达到的最少字数，最小 1

    private transient boolean r18FilterTempEnableInitialed = false;
    private transient boolean r18FilterTempEnable = false; // 临时开启R18内容过滤

    private String searchDefaultSortType = ""; // 搜索结果默认排序方式

    private boolean searchExitConfirm = false; // 搜索结果页退出二次确认（issue #939），默认关闭

    private boolean feedBackToTopFab = false; // 搜索结果页 / 画师主页列表右下角「回顶」悬浮钮（issue #1040），默认关闭

    private String navigationInitPosition = NavigationLocationHelper.TUIJIAN; // 主页底部导航栏初始化位置

//    private boolean isDownloadOnlyUseWiFi = false; // 仅通过 Wifi 下载

    private int downloadLimitType = 0; // 下载限制类型 0:无限制 1:仅Wifi下自动下载 2:不自动下载

    /** 同时下载的最大任务数（1-5）。1 = 严格串行（旧默认行为）。 */
    private int maxConcurrentDownloads = 1;

    /** 桌面小组件换图间隔（分钟），只作用于推荐类小组件；日榜固定 6 小时。WorkManager 下限 15。 */
    private int widgetRefreshIntervalMinutes = 30;

    /** 平板适配排版（侧边导航栏 / 瀑布流按宽度加列 / 作品舞台详情，#1087），见 TabletLayout。默认关闭。 */
    private boolean tabletLayout = false;

    /** 隐藏小组件上浮在封面之上的收藏按钮（#1013：挡画面） */
    private boolean widgetHideBookmarkButton = false;
    /** 隐藏小组件上浮在封面之上的刷新按钮（#1013：挡画面） */
    private boolean widgetHideRefreshButton = false;

    // ===== aria2 远程下载（#692）：启用后图片下载任务通过 JSON-RPC 发给远端 aria2（如 NAS），不在本地落盘 =====
    private boolean aria2Enabled = false;
    /** aria2 JSON-RPC 端点，如 http://192.168.1.5:6800/jsonrpc */
    private String aria2RpcUrl = "";
    /** aria2 RPC 密钥（--rpc-secret），可空 */
    private String aria2RpcSecret = "";
    /** 远端下载目录（aria2 的 dir 选项），可空 = 使用 aria2 全局配置 */
    private String aria2RemoteDir = "";

    // ===== 自定义 AI 翻译（#975）：启用后评论/漫画翻译走 OpenAI 兼容接口，替代内置 Google web 端点 =====
    private boolean aiTranslateEnabled = false;
    /** OpenAI 兼容 base URL，如 https://api.openai.com/v1（自动补 /chat/completions） */
    private String aiTranslateBaseUrl = "";
    /** API key，本地部署（Ollama 等）可空 */
    private String aiTranslateApiKey = "";
    /** 模型名，如 gpt-4o-mini / deepseek-v4-flash / sakura-14b */
    private String aiTranslateModel = "";
    /** 自定义系统提示词，可空 = 使用内置翻译提示词 */
    private String aiTranslatePrompt = "";
    /** 思考参数模式：0=默认不加(平台默认)，1=DeepSeek thinking.type=disabled，2=SiliconFlow/千问 enable_thinking=false，3=OpenAI 系 reasoning_effort=low */
    private int aiTranslateThinkingMode = 0;
    /** 流式传输(SSE)，默认开启；失败自动降级非流式 */
    private boolean aiTranslateStreaming = true;
    /** OkHttp readTimeout 秒数，默认 120；思考型模型可调大（30~600） */
    private int aiTranslateReadTimeoutSeconds = 120;

    /**
     * PixShaft 云翻译（服务端代理的 AI 翻译，按字符计额度）。默认开：这是给没有代理、也没有自己
     * API key 的大多数用户准备的。自定义 AI 翻译启用时优先自定义；两者都关走内置 Google 翻译。
     */
    private boolean cloudTranslateEnabled = true;

    /** 已完成 tab 的列表展示模式（0=横向列表，1=网格 2 列，2=紧凑缩图 4 列）。1 = 旧默认。 */
    private int doneListLayoutMode = 1;

    private boolean illustDetailKeepScreenOn = false; //插画二级详情保持屏幕常亮

    // 看图时为状态栏(刘海/挖孔)留出顶部空间，避免多图/竖图铺满顶部被遮挡（issue #724）。默认关闭，保持原沉浸式铺满。
    private boolean keepStatusBarWhenViewImage = false;

    // 收藏夹过滤已失效作品（已删除/不可见），默认不过滤
    private boolean filterInvalidBookmarks = false;

    // 收藏镜像总开关，默认开启。开启后，用户**打开过**的收藏页（插画/小说 × 公开/悄悄收藏）
    // 会被后台以 5 秒一页的限速静默镜像进本地库，之后才能倒序/按标签/按作者/按年份筛选。
    // 关闭后引擎立即停工（已镜像的数据保留，可在收藏页的本地库入口里手动清除）。
    private boolean bookmarkMirrorEnabled = true;

    // 同义词词典功能总开关（issue #904），默认关闭。
    // 关闭时所有相关 UI（详情页匹配框/长按菜单项/管理页入口/自动导入/自动勾选）完全隐藏
    private boolean synonymDictEnabled = false;

    // 动图(ugoira) RIFE AI 补帧开关**不在这里**:它跟「本机模型是否落盘」绑定,不具备跨设备性,
    // 已搬到设备本地 MMKV 的 ceui.pixiv.ui.interpolate.RifePrefs,不进备份 / 云端。

    // 详情页动图(ugoira)自动播放,默认开启(行为不变)。关闭后进详情不自动下载/播放,
    // 图片中间显示「开始播放(下载)」按钮;已缓存或左右切回也不自动播,点按钮才开始。
    private boolean autoPlayUgoira = true;

    /** 二级大图 ↔ 一级详情页视口联动：不跟随（默认）。 */
    public static final int VIEWER_VIEWPORT_LINK_NONE = 0;
    /** 仅详情页已展开时跟随。 */
    public static final int VIEWER_VIEWPORT_LINK_EXPANDED_ONLY = 1;
    /** 详情页折叠时自动展开并跟随。 */
    public static final int VIEWER_VIEWPORT_LINK_AUTO_EXPAND = 2;

    // 二级大图 ↔ 一级详情页视口联动（fork）。默认「不跟随」：开启后在大图翻页会驱动详情页滚到同一页，
    // 退出时图片缩回它在详情页里的那一格（而不是凭空沿手势方向滑走）。
    private int viewerViewportLinkMode = VIEWER_VIEWPORT_LINK_NONE;

    /** 动图保存成 GIF。体积大(20MB 量级)、只有 256 色,但兼容性最好。 */
    public static final int UGOIRA_SAVE_FORMAT_GIF = 0;

    /** 动图保存成 H.264 mp4(默认)。体积约为 GIF 的 1/10,全彩,且播放缓存里已经压好。 */
    public static final int UGOIRA_SAVE_FORMAT_MP4 = 1;

    // 动图保存格式。默认 MP4:同一条动图 GIF 要 20MB+ 且只有 256 色,H.264 一两 MB 还全彩,
    // 播放缓存里本来就压好了一份,保存基本是纯拷贝。用 int 而不是 boolean 是给以后的格式
    // (实况照片等)留位置。老用户配置里没有这个 key 时 gson 保留字段初值,同样是 MP4。
    private int ugoiraSaveFormat = UGOIRA_SAVE_FORMAT_MP4;

    // 冷启动时是否自动刷新首页推荐插画（issue #955），默认开启（保持本地优先的原语义）。
    // 关掉后冷启命中磁盘快照就停在快照上，由用户下拉刷新才拉新内容
    private boolean autoRefreshHomeFeed = true;

    // 推荐页页签顺序：默认推荐作品在前；插画和小说共用，重启后生效。
    private boolean recommendHotTagsFirst = false;

    public boolean isRecommendHotTagsFirst() {
        return recommendHotTagsFirst;
    }

    public void setRecommendHotTagsFirst(boolean recommendHotTagsFirst) {
        this.recommendHotTagsFirst = recommendHotTagsFirst;
    }

    /** @deprecated legacy display-name language；仅供 AppLocalesBootstrap 一次性迁移读取，请使用 {@link ceui.pixiv.i18n.AppLocales}。 */
    @Deprecated
    public String getAppLanguage() {
        return appLanguage == null ? "" : appLanguage;
    }

    public boolean isToastDownloadResult() {
        return toastDownloadResult;
    }

    public void setToastDownloadResult(boolean toastDownloadResult) {
        this.toastDownloadResult = toastDownloadResult;
    }

    /**
     * 被安静的下载提示消息键集合；空集 = 全开。这里只保证非 null，不做白名单过滤 ——
     * 认不认得出集合里的键由 {@link ceui.pixiv.download.toast.DownloadToasts} 决定。
     */
    public LinkedHashSet<String> getMutedDownloadToasts() {
        if (mutedDownloadToasts == null) {
            mutedDownloadToasts = new LinkedHashSet<>();
        }
        return mutedDownloadToasts;
    }

    public void setMutedDownloadToasts(LinkedHashSet<String> mutedDownloadToasts) {
        this.mutedDownloadToasts = mutedDownloadToasts == null
                ? new LinkedHashSet<>() : mutedDownloadToasts;
    }

    public boolean isAutoExportIllustCaption() {
        return autoExportIllustCaption;
    }

    public void setAutoExportIllustCaption(boolean autoExportIllustCaption) {
        this.autoExportIllustCaption = autoExportIllustCaption;
    }

    public int getAutoExportCaptionMinLength() {
        return autoExportCaptionMinLength;
    }

    public void setAutoExportCaptionMinLength(int autoExportCaptionMinLength) {
        this.autoExportCaptionMinLength = autoExportCaptionMinLength;
    }

    public int getDownloadWay() {
        return downloadWay;
    }

    public void setDownloadWay(int downloadWay) {
        this.downloadWay = downloadWay;
    }

    public boolean isR18DivideSave() {
        return r18DivideSave;
    }

    public void setR18DivideSave(boolean r18DivideSave) {
        this.r18DivideSave = r18DivideSave;
    }

    public boolean isAIDivideSave() {
        return AIDivideSave;
    }

    public void setAIDivideSave(boolean AIDivideSave) {
        this.AIDivideSave = AIDivideSave;
    }

    public String getRootPathUri() {
        return rootPathUri;
    }

    public void setRootPathUri(String rootPathUri) {
        this.rootPathUri = rootPathUri;
    }

    public String getNovelPath() {
        return TextUtils.isEmpty(novelPath) ? FILE_LOG_PATH : novelPath;
    }

    public boolean isPrivateStar() {
        return privateStar;
    }

    public void setPrivateStar(boolean privateStar) {
        this.privateStar = privateStar;
    }

    public boolean isPrivateFollow() {
        return privateFollow;
    }

    public void setPrivateFollow(boolean privateFollow) {
        this.privateFollow = privateFollow;
    }

    public void setNovelPath(String novelPath) {
        this.novelPath = novelPath;
    }

    /** @deprecated 仅供迁移使用，见 {@link ceui.pixiv.i18n.AppLocales}。 */
    @Deprecated
    public void setAppLanguage(String appLanguage) {
        this.appLanguage = appLanguage;
    }

    public ThemeHelper.ThemeType getThemeType() {
        try {
            return ThemeHelper.ThemeType.valueOf(themeType);
        }catch (Exception e){
            return ThemeHelper.ThemeType.DEFAULT_MODE;
        }
    }

    public boolean isFirebaseEnable() {
        return isFirebaseEnable;
    }

    public void setFirebaseEnable(boolean firebaseEnable) {
        isFirebaseEnable = firebaseEnable;
    }

    public boolean isLogFileEnabled() {
        return logFileEnabled;
    }

    public void setLogFileEnabled(boolean logFileEnabled) {
        this.logFileEnabled = logFileEnabled;
    }

    public LinkedHashSet<String> getPredictiveBackDisabledActivities() {
        if (predictiveBackDisabledActivities == null) {
            predictiveBackDisabledActivities = new LinkedHashSet<>();
        }
        return predictiveBackDisabledActivities;
    }

    public void setPredictiveBackDisabledActivities(LinkedHashSet<String> predictiveBackDisabledActivities) {
        this.predictiveBackDisabledActivities = predictiveBackDisabledActivities == null
                ? new LinkedHashSet<>()
                : predictiveBackDisabledActivities;
    }

    public boolean isDrawerPredictiveBackEnabled() {
        return drawerPredictiveBackEnabled;
    }

    public void setDrawerPredictiveBackEnabled(boolean drawerPredictiveBackEnabled) {
        this.drawerPredictiveBackEnabled = drawerPredictiveBackEnabled;
    }

    public boolean isSuppressBackFlickerAnyPage() {
        return suppressBackFlickerAnyPage;
    }

    public void setSuppressBackFlickerAnyPage(boolean suppressBackFlickerAnyPage) {
        this.suppressBackFlickerAnyPage = suppressBackFlickerAnyPage;
    }

    public void setThemeType(AppCompatActivity activity, ThemeHelper.ThemeType themeType) {
        this.themeType = themeType.name();
        ThemeHelper.applyTheme(activity, themeType);
    }

    public boolean isDeleteStarIllust() {
        return deleteStarIllust;
    }

    public void setDeleteStarIllust(boolean pDeleteStarIllust) {
        deleteStarIllust = pDeleteStarIllust;
    }

    public boolean isFilterRankBookmarked() {
        return filterRankBookmarked;
    }

    public void setFilterRankBookmarked(boolean filterRankBookmarked) {
        this.filterRankBookmarked = filterRankBookmarked;
    }

    public boolean isSearchFilterBookmarked() {
        return searchFilterBookmarked;
    }

    public void setSearchFilterBookmarked(boolean searchFilterBookmarked) {
        this.searchFilterBookmarked = searchFilterBookmarked;
    }

    public boolean isDeleteAIIllust() {
        return deleteAIIllust;
    }

    public void setDeleteAIIllust(boolean b) {
        deleteAIIllust = b;
    }

    public int getAiBlockStrength() {
        return aiBlockStrength;
    }

    public void setAiBlockStrength(int aiBlockStrength) {
        this.aiBlockStrength = aiBlockStrength == 1 ? 1 : 0;
    }

    public LinkedHashSet<Long> getAiBlockExemptAuthorIds() {
        if (aiBlockExemptAuthorIds == null) {
            aiBlockExemptAuthorIds = new LinkedHashSet<>();
        }
        return aiBlockExemptAuthorIds;
    }

    public void setAiBlockExemptAuthorIds(LinkedHashSet<Long> aiBlockExemptAuthorIds) {
        this.aiBlockExemptAuthorIds = aiBlockExemptAuthorIds == null
                ? new LinkedHashSet<>()
                : aiBlockExemptAuthorIds;
    }

    /** 屏蔽 AI 是否需要客户端接管（拿全量再本地滤/遮）：模糊粒子化或存在豁免作者时，服务端不能直接剔掉 AI。 */
    public boolean isAiBlockClientSide() {
        return aiBlockStrength != 0 || !getAiBlockExemptAuthorIds().isEmpty();
    }


    private String themeType = "";

    //收藏量筛选搜索结果
    private String searchFilter = "";

    public Settings() {
    }

    public boolean isSaveViewHistory() {
        return saveViewHistory;
    }

    public void setSaveViewHistory(boolean saveViewHistory) {
        this.saveViewHistory = saveViewHistory;
    }

    public boolean isCloudHistorySync() {
        return cloudHistorySync;
    }

    public void setCloudHistorySync(boolean cloudHistorySync) {
        this.cloudHistorySync = cloudHistorySync;
    }

    public boolean isCloudHistoryConsentShown() {
        return cloudHistoryConsentShown;
    }

    public void setCloudHistoryConsentShown(boolean cloudHistoryConsentShown) {
        this.cloudHistoryConsentShown = cloudHistoryConsentShown;
    }

    public long getCloudHistoryBackfillDoneUid() {
        return cloudHistoryBackfillDoneUid;
    }

    public void setCloudHistoryBackfillDoneUid(long cloudHistoryBackfillDoneUid) {
        this.cloudHistoryBackfillDoneUid = cloudHistoryBackfillDoneUid;
    }

    public String getSearchFilter() {
        return TextUtils.isEmpty(searchFilter) ? "" : searchFilter;
    }

    // issue #865: 图片加速代理模式。0=Pixiv 官方(i.pximg.net) 1=pixiv.cat 2=pixiv.re 3=pixiv.nl 4=自定义反代。
    // 对应 ceui.lisa.http.ImageHostManager.Mode 的 ordinal。
    public int getImageHostMode() {
        return imageHostMode;
    }

    public void setImageHostMode(int imageHostMode) {
        this.imageHostMode = imageHostMode;
    }

    public String getCustomImageHost() {
        return customImageHost == null ? "" : customImageHost;
    }

    public void setCustomImageHost(String customImageHost) {
        this.customImageHost = customImageHost;
    }

    /** App API 代理是否启用：**地址非空即启用**（空 = 不代理）。由设置页独立输入选项驱动。 */
    public boolean isUseAppApiProxy() {
        return !TextUtils.isEmpty(appApiProxy);
    }

    public String getAppApiProxy() {
        return appApiProxy == null ? "" : appApiProxy;
    }

    public void setAppApiProxy(String appApiProxy) {
        this.appApiProxy = appApiProxy;
    }

    /** GitHub 加速地址（已保存的原始字符串）；空 = 不使用，见 {@link ceui.lisa.http.GithubProxy}。 */
    public String getGithubProxy() {
        return githubProxy == null ? "" : githubProxy;
    }

    public void setGithubProxy(String githubProxy) {
        this.githubProxy = githubProxy;
    }

    public void setSearchFilter(String searchFilter) {
        this.searchFilter = searchFilter;
    }

    public boolean isRelatedIllustNoLimit() {
        return relatedIllustNoLimit;
    }

    public void setRelatedIllustNoLimit(boolean relatedIllustNoLimit) {
        this.relatedIllustNoLimit = relatedIllustNoLimit;
    }

    public boolean isDirectConnect() {
        return directConnect;
    }

    public void setDirectConnect(boolean directConnect) {
        this.directConnect = directConnect;
    }

    public boolean isUseSecureDns() {
        return useSecureDns;
    }

    public void setUseSecureDns(boolean useSecureDns) {
        this.useSecureDns = useSecureDns;
    }

    public boolean isMainViewR18() {
        return mainViewR18;
    }

    public void setMainViewR18(boolean mainViewR18) {
        this.mainViewR18 = mainViewR18;
    }

    public boolean isUseArtworkV3() {
        return useArtworkV3;
    }

    public void setUseArtworkV3(boolean useArtworkV3) {
        this.useArtworkV3 = useArtworkV3;
    }

    public boolean isNovelListDirectToReader() {
        return novelListDirectToReader;
    }

    public void setNovelListDirectToReader(boolean novelListDirectToReader) {
        this.novelListDirectToReader = novelListDirectToReader;
    }

    public boolean isDetailPanelCollapsedByDefault() {
        return detailPanelCollapsedByDefault;
    }

    public void setDetailPanelCollapsedByDefault(boolean detailPanelCollapsedByDefault) {
        this.detailPanelCollapsedByDefault = detailPanelCollapsedByDefault;
    }

    public boolean isViewHistoryAnimate() {
        return viewHistoryAnimate;
    }

    public void setViewHistoryAnimate(boolean viewHistoryAnimate) {
        this.viewHistoryAnimate = viewHistoryAnimate;
    }

    public boolean isSettingsAnimate() {
        return settingsAnimate;
    }

    public void setSettingsAnimate(boolean settingsAnimate) {
        this.settingsAnimate = settingsAnimate;
    }

    public boolean isDirectDownloadAllImage() {
        return directDownloadAllImage;
    }

    public void setDirectDownloadAllImage(boolean directDownloadAllImage) {
        this.directDownloadAllImage = directDownloadAllImage;
    }

    public boolean isWriteTagsToImageExif() {
        return writeTagsToImageExif;
    }

    public void setWriteTagsToImageExif(boolean writeTagsToImageExif) {
        this.writeTagsToImageExif = writeTagsToImageExif;
    }

    public boolean isSilentDownload() {
        return silentDownload;
    }

    public void setSilentDownload(boolean silentDownload) {
        this.silentDownload = silentDownload;
    }

    public String getIllustPath() {
        return TextUtils.isEmpty(illustPath) ? FILE_PATH_SINGLE : illustPath;
    }

    public void setIllustPath(String illustPath) {
        this.illustPath = illustPath;
    }

    public String getGifResultPath() {
        return TextUtils.isEmpty(gifResultPath) ? FILE_GIF_RESULT_PATH : gifResultPath;
    }

    public void setGifResultPath(String gifResultPath) {
        this.gifResultPath = gifResultPath;
    }

    public String getGifZipPath() {
        return TextUtils.isEmpty(gifZipPath) ? FILE_GIF_PATH : gifZipPath;
    }

    public void setGifZipPath(String gifZipPath) {
        this.gifZipPath = gifZipPath;
    }

    public String getGifUnzipPath() {
        return TextUtils.isEmpty(gifUnzipPath) ? FILE_GIF_CHILD_PATH : gifUnzipPath;
    }

    public void setGifUnzipPath(String gifUnzipPath) {
        this.gifUnzipPath = gifUnzipPath;
    }

    public String getWebDownloadPath() {
        return TextUtils.isEmpty(webDownloadPath) ? WEB_DOWNLOAD_PATH : "webDownloadPath";
    }

    public void setWebDownloadPath(String webDownloadPath) {
        this.webDownloadPath = webDownloadPath;
    }

    public boolean isTrendsForPrivate() {
        return trendsForPrivate;
    }

    public void setTrendsForPrivate(boolean trendsForPrivate) {
        this.trendsForPrivate = trendsForPrivate;
    }

    public boolean isShowPixivDialog() {
        return showPixivDialog;
    }

    public void setShowPixivDialog(boolean showPixivDialog) {
        this.showPixivDialog = showPixivDialog;
    }

    public boolean isReverseDialogNeverShowAgain() {
        return reverseDialogNeverShowAgain;
    }

    public void setReverseDialogNeverShowAgain(boolean reverseDialogNeverShowAgain) {
        this.reverseDialogNeverShowAgain = reverseDialogNeverShowAgain;
    }

    public boolean isShowLikeButton() {
        return showLikeButton;
    }

    public void setShowLikeButton(boolean pShowLikeButton) {
        showLikeButton = pShowLikeButton;
    }

    public boolean isLikeHapticEnable() {
        return likeHapticEnable;
    }

    public void setLikeHapticEnable(boolean likeHapticEnable) {
        this.likeHapticEnable = likeHapticEnable;
    }

    public String getFileNameJson() {
        return fileNameJson;
    }

    public void setFileNameJson(String fileNameJson) {
        this.fileNameJson = fileNameJson;
    }

    public boolean isHasP0() {
        return hasP0;
    }

    public void setHasP0(boolean hasP0) {
        this.hasP0 = hasP0;
    }

    public int getNovelHolderColor() {
        return novelHolderColor;
    }

    public void setNovelHolderColor(int novelHolderColor) {
        this.novelHolderColor = novelHolderColor;
    }

    public int getNovelHolderTextColor() {
        return novelHolderTextColor;
    }

    public void setNovelHolderTextColor(int novelHolderTextColor) {
        this.novelHolderTextColor = novelHolderTextColor;
    }

    public int getNovelHolderTextSize() {
        return novelHolderTextSize;
    }
    
    public void setNovelHolderTextSize(int size) {
        this.novelHolderTextSize = size;
    }

    public int getBottomBarOrder() {
        return bottomBarOrder;
    }

    public void setBottomBarOrder(int bottomBarOrder) {
        this.bottomBarOrder = bottomBarOrder;
    }

    public boolean isHideStarButtonAtMyCollection() {
        return hideStarButtonAtMyCollection;
    }

    public void setHideStarButtonAtMyCollection(boolean hideStarButtonAtMyCollection) {
        this.hideStarButtonAtMyCollection = hideStarButtonAtMyCollection;
    }

    public boolean isStarWithTagSelectAll() {
        return starWithTagSelectAll;
    }

    public void setStarWithTagSelectAll(boolean starWithTagSelectAll) {
        this.starWithTagSelectAll = starWithTagSelectAll;
    }

    public boolean isFilterComment() {
        return filterComment;
    }

    public void setFilterComment(boolean filterComment) {
        this.filterComment = filterComment;
    }

    public int getTransformerType() {
        return transformerType;
    }

    public void setTransformerType(int transformerType) {
        this.transformerType = transformerType;
    }

    public boolean isShowRelatedWhenStar() {
        return showRelatedWhenStar;
    }

    public void setShowRelatedWhenStar(boolean showRelatedWhenStar) {
        this.showRelatedWhenStar = showRelatedWhenStar;
    }

    public boolean isIllustLongPressDownload() {
        return illustLongPressDownload;
    }

    public void setIllustLongPressDownload(boolean illustLongPressDownload) {
        this.illustLongPressDownload = illustLongPressDownload;
    }

    public boolean isAutoPostLikeWhenDownload() {
        return autoPostLikeWhenDownload;
    }

    public void setAutoPostLikeWhenDownload(boolean autoPostLikeWhenDownload) {
        this.autoPostLikeWhenDownload = autoPostLikeWhenDownload;
    }

    public boolean isAutoFollowAfterStar() {
        return autoFollowAfterStar;
    }

    public void setAutoFollowAfterStar(boolean autoFollowAfterStar) {
        this.autoFollowAfterStar = autoFollowAfterStar;
    }

    public boolean isAutoDownloadAfterStar() {
        return autoDownloadAfterStar;
    }

    public void setAutoDownloadAfterStar(boolean autoDownloadAfterStar) {
        this.autoDownloadAfterStar = autoDownloadAfterStar;
    }

    public boolean isAutoSnapshotOnBookmark() {
        return autoSnapshotOnBookmark;
    }

    public void setAutoSnapshotOnBookmark(boolean autoSnapshotOnBookmark) {
        this.autoSnapshotOnBookmark = autoSnapshotOnBookmark;
    }

    public boolean isAutoSnapshotOnIllustManga() {
        return autoSnapshotOnIllustManga;
    }

    public void setAutoSnapshotOnIllustManga(boolean autoSnapshotOnIllustManga) {
        this.autoSnapshotOnIllustManga = autoSnapshotOnIllustManga;
    }

    public int getAutoSnapshotMaxMb() {
        return AutoSnapshotQuota.clampLimitMb(autoSnapshotMaxMb);
    }

    public void setAutoSnapshotMaxMb(int autoSnapshotMaxMb) {
        this.autoSnapshotMaxMb = AutoSnapshotQuota.clampLimitMb(autoSnapshotMaxMb);
    }

    public int getImageCacheMaxMb() {
        return ImageCacheQuota.clampLimitMb(imageCacheMaxMb);
    }

    public void setImageCacheMaxMb(int imageCacheMaxMb) {
        this.imageCacheMaxMb = ImageCacheQuota.clampLimitMb(imageCacheMaxMb);
    }

    public int getImageReadTimeoutSeconds() {
        return ImageReadTimeout.clampSeconds(imageReadTimeoutSeconds, isDirectConnect());
    }

    public void setImageReadTimeoutSeconds(int imageReadTimeoutSeconds) {
        this.imageReadTimeoutSeconds =
                ImageReadTimeout.clampSeconds(imageReadTimeoutSeconds, isDirectConnect());
    }

    /**
     * 切换直连开关时调用：读超时分直连 / 非直连两种量程，用户此前调的值跨模式不再适用，重置为
     * 新模式的默认值（= 该模式上界）。
     */
    public void resetImageReadTimeout() {
        this.imageReadTimeoutSeconds = ImageReadTimeout.defaultSeconds(isDirectConnect());
    }

    /**
     * 读超时是否被用户**调小过**（当前值 ≠ 该模式默认值）。
     *
     * 用于「读超时静默重连」的判定：只有主动调小阈值的人，才是在用「更早断流」换「更快重连」，
     * 那一下读超时对他才是预期内的；停在默认（= 上界）时读超时照旧算失败，行为与历史一致。
     */
    public boolean isImageReadTimeoutLowered() {
        return ImageReadTimeout.isLoweredThanDefault(
                imageReadTimeoutSeconds, isDirectConnect());
    }

    /** 见 {@link #imageLoadRetryOnStall}。默认关。 */
    public boolean isImageLoadRetryOnStall() {
        return imageLoadRetryOnStall;
    }

    /** 见 {@link #imageLoadRetryOnStall}。改完立即生效，不需要重启。 */
    public void setImageLoadRetryOnStall(boolean imageLoadRetryOnStall) {
        this.imageLoadRetryOnStall = imageLoadRetryOnStall;
    }

    public boolean isShowOriginalPreviewImage() {
        return showOriginalPreviewImage;
    }

    public void setShowOriginalPreviewImage(boolean showOriginalPreviewImage) {
        this.showOriginalPreviewImage = showOriginalPreviewImage;
    }

    public boolean isR18FilterDefaultEnable() {
        return r18FilterDefaultEnable;
    }

    public void setR18FilterDefaultEnable(boolean r18FilterDefaultEnable) {
        this.r18FilterDefaultEnable = r18FilterDefaultEnable;
    }

    public boolean isR18FilterTempEnable() {
        if (!r18FilterTempEnableInitialed) {
            r18FilterTempEnable = r18FilterDefaultEnable;
            r18FilterTempEnableInitialed = true;
        }
        return r18FilterTempEnable;
    }

    public void setR18FilterTempEnable(boolean r18FilterTempEnable) {
        this.r18FilterTempEnable = r18FilterTempEnable;
    }

    public int getNovelFilterMinTextLength() {
        return novelFilterMinTextLength;
    }

    public void setNovelFilterMinTextLength(int novelFilterMinTextLength) {
        this.novelFilterMinTextLength = novelFilterMinTextLength;
    }

    public int getNovelFilterMaxTextLength() {
        return novelFilterMaxTextLength;
    }

    public void setNovelFilterMaxTextLength(int novelFilterMaxTextLength) {
        this.novelFilterMaxTextLength = novelFilterMaxTextLength;
    }

    public int getNovelFilterMaxTagNameLength() {
        return novelFilterMaxTagNameLength;
    }

    public void setNovelFilterMaxTagNameLength(int novelFilterMaxTagNameLength) {
        this.novelFilterMaxTagNameLength = novelFilterMaxTagNameLength;
    }

    public String getNavigationInitPosition() {
        return navigationInitPosition;
    }

    public void setNavigationInitPosition(String navigationInitPosition) {
        this.navigationInitPosition = navigationInitPosition;
    }

    public String getSearchDefaultSortType() {
        // 默认排序：popular_desc（按热度）—— 搜索的默认诉求是「先看好的」，不是「先看新的」。
        // 它仍走 searchIllust/searchNovel 端点（sort 透传），所以 lang 等 query 筛选照常生效；
        // 非会员由 SearchIllustRepo/SearchNovelRepo 的借号路线跑，借不到时回落 popular-preview。
        return TextUtils.isEmpty(searchDefaultSortType) ? PixivSearchParamUtil.POPULAR_SORT_VALUE : searchDefaultSortType;
    }

    public void setSearchDefaultSortType(String searchDefaultSortType) {
        this.searchDefaultSortType = searchDefaultSortType;
    }

    public boolean isSearchExitConfirm() {
        return searchExitConfirm;
    }

    public void setSearchExitConfirm(boolean searchExitConfirm) {
        this.searchExitConfirm = searchExitConfirm;
    }

    public boolean isFeedBackToTopFab() {
        return feedBackToTopFab;
    }

    public void setFeedBackToTopFab(boolean feedBackToTopFab) {
        this.feedBackToTopFab = feedBackToTopFab;
    }

    public boolean isScrollEdgeHapticEnable() {
        return scrollEdgeHapticEnable;
    }

    public void setScrollEdgeHapticEnable(boolean scrollEdgeHapticEnable) {
        this.scrollEdgeHapticEnable = scrollEdgeHapticEnable;
    }

    public int getSaveForSeparateAuthorStatus() {
        return saveForSeparateAuthorStatus;
    }

    public void setSaveForSeparateAuthorStatus(int saveForSeparateAuthorStatus) {
        this.saveForSeparateAuthorStatus = saveForSeparateAuthorStatus;
    }

    public int getDownloadLimitType() {
        return downloadLimitType;
    }

    public void setDownloadLimitType(int downloadLimitType) {
        this.downloadLimitType = downloadLimitType;
    }

    /** clamp 到 [1,5]；老用户/损坏配置（值为 0 / 负数 / 大于 5）都按 1 处理 */
    public int getMaxConcurrentDownloads() {
        if (maxConcurrentDownloads < 1) return 1;
        if (maxConcurrentDownloads > 5) return 5;
        return maxConcurrentDownloads;
    }

    public void setMaxConcurrentDownloads(int n) {
        if (n < 1) n = 1;
        if (n > 5) n = 5;
        this.maxConcurrentDownloads = n;
    }

    /** 低于 WorkManager 周期任务下限 15 的值视为损坏配置，按默认 30 处理 */
    public int getWidgetRefreshIntervalMinutes() {
        if (widgetRefreshIntervalMinutes < 15) return 30;
        return widgetRefreshIntervalMinutes;
    }

    public void setWidgetRefreshIntervalMinutes(int minutes) {
        this.widgetRefreshIntervalMinutes = minutes;
    }

    public boolean isTabletLayout() {
        return tabletLayout;
    }

    public void setTabletLayout(boolean enable) {
        this.tabletLayout = enable;
    }

    public boolean isWidgetHideBookmarkButton() {
        return widgetHideBookmarkButton;
    }

    public void setWidgetHideBookmarkButton(boolean hide) {
        this.widgetHideBookmarkButton = hide;
    }

    public boolean isWidgetHideRefreshButton() {
        return widgetHideRefreshButton;
    }

    public void setWidgetHideRefreshButton(boolean hide) {
        this.widgetHideRefreshButton = hide;
    }

    public boolean isAria2Enabled() {
        return aria2Enabled;
    }

    public void setAria2Enabled(boolean aria2Enabled) {
        this.aria2Enabled = aria2Enabled;
    }

    public String getAria2RpcUrl() {
        return aria2RpcUrl == null ? "" : aria2RpcUrl;
    }

    public void setAria2RpcUrl(String aria2RpcUrl) {
        this.aria2RpcUrl = aria2RpcUrl;
    }

    public String getAria2RpcSecret() {
        return aria2RpcSecret == null ? "" : aria2RpcSecret;
    }

    public void setAria2RpcSecret(String aria2RpcSecret) {
        this.aria2RpcSecret = aria2RpcSecret;
    }

    public String getAria2RemoteDir() {
        return aria2RemoteDir == null ? "" : aria2RemoteDir;
    }

    public void setAria2RemoteDir(String aria2RemoteDir) {
        this.aria2RemoteDir = aria2RemoteDir;
    }

    public boolean isCloudTranslateEnabled() {
        return cloudTranslateEnabled;
    }

    public void setCloudTranslateEnabled(boolean cloudTranslateEnabled) {
        this.cloudTranslateEnabled = cloudTranslateEnabled;
    }

    public boolean isAiTranslateEnabled() {
        return aiTranslateEnabled;
    }

    public void setAiTranslateEnabled(boolean aiTranslateEnabled) {
        this.aiTranslateEnabled = aiTranslateEnabled;
    }

    public String getAiTranslateBaseUrl() {
        return aiTranslateBaseUrl == null ? "" : aiTranslateBaseUrl;
    }

    public void setAiTranslateBaseUrl(String aiTranslateBaseUrl) {
        this.aiTranslateBaseUrl = aiTranslateBaseUrl;
    }

    public String getAiTranslateApiKey() {
        return aiTranslateApiKey == null ? "" : aiTranslateApiKey;
    }

    public void setAiTranslateApiKey(String aiTranslateApiKey) {
        this.aiTranslateApiKey = aiTranslateApiKey;
    }

    public String getAiTranslateModel() {
        return aiTranslateModel == null ? "" : aiTranslateModel;
    }

    public void setAiTranslateModel(String aiTranslateModel) {
        this.aiTranslateModel = aiTranslateModel;
    }

    public String getAiTranslatePrompt() {
        return aiTranslatePrompt == null ? "" : aiTranslatePrompt;
    }

    public void setAiTranslatePrompt(String aiTranslatePrompt) {
        this.aiTranslatePrompt = aiTranslatePrompt;
    }

    public int getAiTranslateThinkingMode() {
        return aiTranslateThinkingMode;
    }

    public void setAiTranslateThinkingMode(int aiTranslateThinkingMode) {
        this.aiTranslateThinkingMode = aiTranslateThinkingMode;
    }

    public boolean isAiTranslateStreaming() {
        return aiTranslateStreaming;
    }

    public void setAiTranslateStreaming(boolean aiTranslateStreaming) {
        this.aiTranslateStreaming = aiTranslateStreaming;
    }

    public int getAiTranslateReadTimeoutSeconds() {
        return aiTranslateReadTimeoutSeconds;
    }

    public void setAiTranslateReadTimeoutSeconds(int aiTranslateReadTimeoutSeconds) {
        this.aiTranslateReadTimeoutSeconds = aiTranslateReadTimeoutSeconds;
    }

    /** clamp 到 [0,2]，0=LIST, 1=GRID, 2=COMPACT */
    public int getDoneListLayoutMode() {
        if (doneListLayoutMode < 0) return 1;
        if (doneListLayoutMode > 2) return 1;
        return doneListLayoutMode;
    }

    public void setDoneListLayoutMode(int n) {
        if (n < 0) n = 1;
        if (n > 2) n = 1;
        this.doneListLayoutMode = n;
    }

    public boolean isShowLargeThumbnailImage() {
        return showLargeThumbnailImage;
    }

    public void setShowLargeThumbnailImage(boolean showLargeThumbnailImage) {
        this.showLargeThumbnailImage = showLargeThumbnailImage;
    }

    public boolean isShowIllustCardBookmarkButton() {
        return showIllustCardBookmarkButton;
    }

    public void setShowIllustCardBookmarkButton(boolean showIllustCardBookmarkButton) {
        this.showIllustCardBookmarkButton = showIllustCardBookmarkButton;
    }

    public boolean isShowNovelCardTags() {
        return showNovelCardTags;
    }

    public void setShowNovelCardTags(boolean showNovelCardTags) {
        this.showNovelCardTags = showNovelCardTags;
    }

    public boolean isShowNovelCardTagTranslations() {
        return showNovelCardTagTranslations;
    }

    public void setShowNovelCardTagTranslations(boolean showNovelCardTagTranslations) {
        this.showNovelCardTagTranslations = showNovelCardTagTranslations;
    }

    public boolean isCollapseNovelCardTags() {
        return collapseNovelCardTags;
    }

    public void setCollapseNovelCardTags(boolean collapseNovelCardTags) {
        this.collapseNovelCardTags = collapseNovelCardTags;
    }

    public boolean isIllustDetailKeepScreenOn() {
        return illustDetailKeepScreenOn;
    }

    public void setIllustDetailKeepScreenOn(boolean illustDetailKeepScreenOn) {
        this.illustDetailKeepScreenOn = illustDetailKeepScreenOn;
    }

    public boolean isKeepStatusBarWhenViewImage() {
        return keepStatusBarWhenViewImage;
    }

    public void setKeepStatusBarWhenViewImage(boolean keepStatusBarWhenViewImage) {
        this.keepStatusBarWhenViewImage = keepStatusBarWhenViewImage;
    }

    public boolean isFilterInvalidBookmarks() {
        return filterInvalidBookmarks;
    }

    public void setFilterInvalidBookmarks(boolean filterInvalidBookmarks) {
        this.filterInvalidBookmarks = filterInvalidBookmarks;
    }

    public boolean isBookmarkMirrorEnabled() {
        return bookmarkMirrorEnabled;
    }

    public void setBookmarkMirrorEnabled(boolean bookmarkMirrorEnabled) {
        this.bookmarkMirrorEnabled = bookmarkMirrorEnabled;
    }

    public boolean isSynonymDictEnabled() {
        return synonymDictEnabled;
    }

    public void setSynonymDictEnabled(boolean synonymDictEnabled) {
        this.synonymDictEnabled = synonymDictEnabled;
    }

    public int getUgoiraSaveFormat() {
        return ugoiraSaveFormat;
    }

    public void setUgoiraSaveFormat(int ugoiraSaveFormat) {
        this.ugoiraSaveFormat = ugoiraSaveFormat;
    }

    /**
     * 保存链路问「这次出 mp4 还是 gif」只看这一处,别在各处比对常量。
     *
     * 判据写成「不是 GIF 就是 MP4」而不是「== MP4」:配置被手改过、或者被新版本写进一个
     * 老版本还不认识的格式值时,兜底到默认的 MP4,和设置页的越界兜底口径一致。
     */
    public boolean isUgoiraSaveAsMp4() {
        return ugoiraSaveFormat != UGOIRA_SAVE_FORMAT_GIF;
    }

    public boolean isAutoPlayUgoira() {
        return autoPlayUgoira;
    }

    public void setAutoPlayUgoira(boolean autoPlayUgoira) {
        this.autoPlayUgoira = autoPlayUgoira;
    }

    public int getViewerViewportLinkMode() {
        if (viewerViewportLinkMode < VIEWER_VIEWPORT_LINK_NONE
                || viewerViewportLinkMode > VIEWER_VIEWPORT_LINK_AUTO_EXPAND) {
            return VIEWER_VIEWPORT_LINK_NONE;
        }
        return viewerViewportLinkMode;
    }

    public void setViewerViewportLinkMode(int viewerViewportLinkMode) {
        if (viewerViewportLinkMode < VIEWER_VIEWPORT_LINK_NONE
                || viewerViewportLinkMode > VIEWER_VIEWPORT_LINK_AUTO_EXPAND) {
            this.viewerViewportLinkMode = VIEWER_VIEWPORT_LINK_NONE;
        } else {
            this.viewerViewportLinkMode = viewerViewportLinkMode;
        }
    }

    public boolean isAutoRefreshHomeFeed() {
        return autoRefreshHomeFeed;
    }

    public void setAutoRefreshHomeFeed(boolean autoRefreshHomeFeed) {
        this.autoRefreshHomeFeed = autoRefreshHomeFeed;
    }

    // 插画大图双击缩放行为：
    // 0=默认（ZoomImage 自带双击缩放），1=三级智能缩放，2=增量缩放。
    public static final int DOUBLE_TAP_ZOOM_MODE_DEFAULT = 0;
    public static final int DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL = 1;
    public static final int DOUBLE_TAP_ZOOM_MODE_INCREMENTAL = 2;

    private int doubleTapZoomMode = DOUBLE_TAP_ZOOM_MODE_DEFAULT;

    // 旧版字段（PR#900/901 的开关）。仅用于兼容旧备份/云端还原和旧版降级读取；
    // 新代码统一走 doubleTapZoomMode，不再直接修改这两个字段。
    private boolean useCustomDoubleTapZoom = false;

    private float customZoomAddScale = 1.8f;

    // 插画大图长按行为：
    // 0=无（默认，长按不做事），1=优先缩小一级（找不到更小的一级就落到初始缩放），2=复原至初始缩放。
    public static final int LONG_PRESS_BEHAVIOR_NONE = 0;
    public static final int LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL = 1;
    public static final int LONG_PRESS_BEHAVIOR_RESET_MIN = 2;

    private int longPressBehavior = LONG_PRESS_BEHAVIOR_NONE;

    // 旧版字段（PR#900 的「启用长按复原」开关）。仅用于兼容旧备份/云端还原和旧版降级读取；
    // 新代码统一走 longPressBehavior，不再直接修改这个字段。
    private boolean useCustomLongPressReset = false;

    private boolean useThreeLevelZoo = false;

    public int getDoubleTapZoomMode() {
        if (doubleTapZoomMode < DOUBLE_TAP_ZOOM_MODE_DEFAULT ||
                doubleTapZoomMode > DOUBLE_TAP_ZOOM_MODE_INCREMENTAL) {
            return DOUBLE_TAP_ZOOM_MODE_DEFAULT;
        }
        return doubleTapZoomMode;
    }

    public void setDoubleTapZoomMode(int doubleTapZoomMode) {
        if (doubleTapZoomMode < DOUBLE_TAP_ZOOM_MODE_DEFAULT ||
                doubleTapZoomMode > DOUBLE_TAP_ZOOM_MODE_INCREMENTAL) {
            this.doubleTapZoomMode = DOUBLE_TAP_ZOOM_MODE_DEFAULT;
        } else {
            this.doubleTapZoomMode = doubleTapZoomMode;
        }
        // 同步旧字段：新版本导出的备份里旧版开关仍然可用，降级回旧版时体验不丢。
        this.useCustomDoubleTapZoom = this.doubleTapZoomMode != DOUBLE_TAP_ZOOM_MODE_DEFAULT;
        this.useThreeLevelZoo = this.doubleTapZoomMode == DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL;
    }

    // ── 二级大图「上/下拖动退出」灵敏度 ──────────────────────────────────────
    // 三个值一一对应 DragDismissLayout 的三个可注入阈值；默认值必须与
    // DragDismissLayout.DEFAULT_* 保持一致。旧备份缺这几个 key 时 Gson 走无参构造，
    // 字段初始化器照跑，读出来就是默认值；getter 的范围校验防的是手改备份、
    // 或以后收窄可调范围后磁盘上残留的越界值——越界回落默认，而不是夹到边界。

    /** 松手判定收掉的拖拽距离阈值（相对本布局高度）。越小越灵敏。 */
    public static final float VIEWER_DISMISS_DISTANCE_DEFAULT = 0.18f;
    public static final float VIEWER_DISMISS_DISTANCE_MIN = 0.05f;
    public static final float VIEWER_DISMISS_DISTANCE_MAX = 0.50f;

    /** 快速外甩判定收掉的速度阈值，单位 dp/s。越小越灵敏。 */
    public static final float VIEWER_DISMISS_VELOCITY_DEFAULT = 1200f;
    public static final float VIEWER_DISMISS_VELOCITY_MIN = 300f;
    public static final float VIEWER_DISMISS_VELOCITY_MAX = 4000f;

    /** 拖满时内容缩小比例（跟手阶段的视觉反馈强度）。越大反馈越强。 */
    public static final float VIEWER_DISMISS_SCALE_SHRINK_DEFAULT = 0.3f;
    public static final float VIEWER_DISMISS_SCALE_SHRINK_MIN = 0f;
    public static final float VIEWER_DISMISS_SCALE_SHRINK_MAX = 0.6f;

    private float viewerDismissDistance = VIEWER_DISMISS_DISTANCE_DEFAULT;

    private float viewerDismissVelocity = VIEWER_DISMISS_VELOCITY_DEFAULT;

    private float viewerDismissScaleShrink = VIEWER_DISMISS_SCALE_SHRINK_DEFAULT;

    public float getViewerDismissDistance() {
        if (viewerDismissDistance < VIEWER_DISMISS_DISTANCE_MIN
                || viewerDismissDistance > VIEWER_DISMISS_DISTANCE_MAX) {
            return VIEWER_DISMISS_DISTANCE_DEFAULT;
        }
        return viewerDismissDistance;
    }

    public void setViewerDismissDistance(float viewerDismissDistance) {
        if (Float.isNaN(viewerDismissDistance)
                || viewerDismissDistance < VIEWER_DISMISS_DISTANCE_MIN
                || viewerDismissDistance > VIEWER_DISMISS_DISTANCE_MAX) {
            this.viewerDismissDistance = VIEWER_DISMISS_DISTANCE_DEFAULT;
        } else {
            this.viewerDismissDistance = viewerDismissDistance;
        }
    }

    public float getViewerDismissVelocity() {
        if (viewerDismissVelocity < VIEWER_DISMISS_VELOCITY_MIN
                || viewerDismissVelocity > VIEWER_DISMISS_VELOCITY_MAX) {
            return VIEWER_DISMISS_VELOCITY_DEFAULT;
        }
        return viewerDismissVelocity;
    }

    public void setViewerDismissVelocity(float viewerDismissVelocity) {
        if (Float.isNaN(viewerDismissVelocity)
                || viewerDismissVelocity < VIEWER_DISMISS_VELOCITY_MIN
                || viewerDismissVelocity > VIEWER_DISMISS_VELOCITY_MAX) {
            this.viewerDismissVelocity = VIEWER_DISMISS_VELOCITY_DEFAULT;
        } else {
            this.viewerDismissVelocity = viewerDismissVelocity;
        }
    }

    public float getViewerDismissScaleShrink() {
        if (viewerDismissScaleShrink < VIEWER_DISMISS_SCALE_SHRINK_MIN
                || viewerDismissScaleShrink > VIEWER_DISMISS_SCALE_SHRINK_MAX) {
            return VIEWER_DISMISS_SCALE_SHRINK_DEFAULT;
        }
        return viewerDismissScaleShrink;
    }

    public void setViewerDismissScaleShrink(float viewerDismissScaleShrink) {
        if (Float.isNaN(viewerDismissScaleShrink)
                || viewerDismissScaleShrink < VIEWER_DISMISS_SCALE_SHRINK_MIN
                || viewerDismissScaleShrink > VIEWER_DISMISS_SCALE_SHRINK_MAX) {
            this.viewerDismissScaleShrink = VIEWER_DISMISS_SCALE_SHRINK_DEFAULT;
        } else {
            this.viewerDismissScaleShrink = viewerDismissScaleShrink;
        }
    }

    /**
     * 「放大大图后禁用拖动退出」：开启后只有处在打开时的初始缩放才允许起手竖向拖拽退出，
     * 放大状态下的上下拖留给画面平移，避免误触退出。默认关闭。
     * 字段名是已落盘的 JSON key，语义从「最小缩放」放宽成「初始缩放」后也不改名。
     */
    private boolean viewerDismissOnlyAtMinScale = false;

    public boolean isViewerDismissOnlyAtMinScale() {
        return viewerDismissOnlyAtMinScale;
    }

    public void setViewerDismissOnlyAtMinScale(boolean viewerDismissOnlyAtMinScale) {
        this.viewerDismissOnlyAtMinScale = viewerDismissOnlyAtMinScale;
    }

    @Deprecated
    public boolean isUseCustomDoubleTapZoom() {
        return useCustomDoubleTapZoom;
    }

    @Deprecated
    public boolean isUseThreeLevelZoo() {
        return useThreeLevelZoo;
    }

    @Deprecated
    public void setUseCustomDoubleTapZoom(boolean useCustomDoubleTapZoom) {
        this.useCustomDoubleTapZoom = useCustomDoubleTapZoom;
        if (!useCustomDoubleTapZoom) {
            this.doubleTapZoomMode = DOUBLE_TAP_ZOOM_MODE_DEFAULT;
        } else if (this.doubleTapZoomMode == DOUBLE_TAP_ZOOM_MODE_DEFAULT) {
            this.doubleTapZoomMode = this.useThreeLevelZoo
                    ? DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL
                    : DOUBLE_TAP_ZOOM_MODE_INCREMENTAL;
        }
        this.useThreeLevelZoo = this.doubleTapZoomMode == DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL;
    }

    @Deprecated
    public void setUseThreeLevelZoo(boolean useThreeLevelZoo) {
        this.useThreeLevelZoo = useThreeLevelZoo;
        if (this.doubleTapZoomMode != DOUBLE_TAP_ZOOM_MODE_DEFAULT) {
            this.doubleTapZoomMode = useThreeLevelZoo
                    ? DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL
                    : DOUBLE_TAP_ZOOM_MODE_INCREMENTAL;
        }
        this.useCustomDoubleTapZoom = this.doubleTapZoomMode != DOUBLE_TAP_ZOOM_MODE_DEFAULT;
    }

    public int getLongPressBehavior() {
        if (longPressBehavior < LONG_PRESS_BEHAVIOR_NONE
                || longPressBehavior > LONG_PRESS_BEHAVIOR_RESET_MIN) {
            return LONG_PRESS_BEHAVIOR_NONE;
        }
        return longPressBehavior;
    }

    public void setLongPressBehavior(int longPressBehavior) {
        if (longPressBehavior < LONG_PRESS_BEHAVIOR_NONE
                || longPressBehavior > LONG_PRESS_BEHAVIOR_RESET_MIN) {
            this.longPressBehavior = LONG_PRESS_BEHAVIOR_NONE;
        } else {
            this.longPressBehavior = longPressBehavior;
        }
        // 同步旧字段：旧版只认两态，「优先缩小一级」在旧版会降级成「复原至最小」，至少长按不是空动作。
        this.useCustomLongPressReset = this.longPressBehavior != LONG_PRESS_BEHAVIOR_NONE;
    }

    @Deprecated
    public boolean isUseCustomLongPressReset() {
        return useCustomLongPressReset;
    }

    @Deprecated
    public void setUseCustomLongPressReset(boolean useCustomLongPressReset) {
        this.useCustomLongPressReset = useCustomLongPressReset;
        if (useCustomLongPressReset) {
            this.longPressBehavior = LONG_PRESS_BEHAVIOR_RESET_MIN;
        } else if (this.longPressBehavior == LONG_PRESS_BEHAVIOR_RESET_MIN) {
            this.longPressBehavior = LONG_PRESS_BEHAVIOR_NONE;
        }
    }

    /**
     * 旧版设置/备份/云端还原迁移：把 PR#900/901 的两个开关映射到新的三选一模式。
     * 新版 JSON 已有 doubleTapZoomMode 时保持原值，同时回填旧字段方便降级兼容。
     */
    public static void migrateLegacyDoubleTapZoom(Settings settings) {
        if (settings == null) {
            return;
        }
        int mode = settings.doubleTapZoomMode;
        if (mode < DOUBLE_TAP_ZOOM_MODE_DEFAULT || mode > DOUBLE_TAP_ZOOM_MODE_INCREMENTAL) {
            mode = DOUBLE_TAP_ZOOM_MODE_DEFAULT;
        }
        if (mode == DOUBLE_TAP_ZOOM_MODE_DEFAULT && settings.useCustomDoubleTapZoom) {
            // 旧版 JSON 没有新字段：靠旧开关推导用户原来选的是增量还是三级。
            mode = settings.useThreeLevelZoo
                    ? DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL
                    : DOUBLE_TAP_ZOOM_MODE_INCREMENTAL;
        }
        settings.doubleTapZoomMode = mode;
        settings.useCustomDoubleTapZoom = mode != DOUBLE_TAP_ZOOM_MODE_DEFAULT;
        settings.useThreeLevelZoo = mode == DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL;
    }

    /**
     * 旧版设置/备份/云端还原迁移：把 PR#900 的「启用长按复原」开关映射到新的三选一长按行为。
     * 新版 JSON 已有 longPressBehavior 时保持原值，同时回填旧字段方便降级兼容。
     */
    public static void migrateLegacyLongPressBehavior(Settings settings) {
        if (settings == null) {
            return;
        }
        int behavior = settings.longPressBehavior;
        if (behavior < LONG_PRESS_BEHAVIOR_NONE || behavior > LONG_PRESS_BEHAVIOR_RESET_MIN) {
            behavior = LONG_PRESS_BEHAVIOR_NONE;
        }
        if (behavior == LONG_PRESS_BEHAVIOR_NONE && settings.useCustomLongPressReset) {
            // 旧版 JSON 没有新字段：开关开过就是「复原至最小」。
            behavior = LONG_PRESS_BEHAVIOR_RESET_MIN;
        }
        settings.longPressBehavior = behavior;
        settings.useCustomLongPressReset = behavior != LONG_PRESS_BEHAVIOR_NONE;
    }

    /**
     * 旧版设置/备份/云端还原迁移：「下载完成App内提示」这个总开关升级成「下载相关提示消息」的
     * 逐项开关（{@link #getMutedDownloadToasts()}）。
     *
     * <p>旧字段为 {@code false} 时把用户当时能静音的那几条（逐张完成 / 逐张失败 / aria2 /
     * 收藏后自动下载）种进静音集合 —— 否则升级后它们会借「新字段默认全开」复活，等于把用户的
     * 选择吃掉；为 {@code true} 时（含没设过）留空集，即全开。
     *
     * <p>靠 {@link #downloadToastsMigrated} 只推导一次：否则用户在新版里把全部提示重新打开后
     * （那时旧开关已被反向回填成 {@code false}），下一次装载会把当年那四条又种回去。
     *
     * <p>反向回填：任一条被安静就把旧开关写成 {@code false}。旧版只有一个总开关，降级回去只能
     * 退化成全静音，但至少方向一致，不会让用户以为设置没生效。
     */
    public static void migrateLegacyDownloadToasts(Settings settings) {
        if (settings == null) {
            return;
        }
        if (!settings.downloadToastsMigrated) {
            settings.downloadToastsMigrated = true;
            // 旧字段为 false 才说明用户当年明确要安静；为 true（含没设过）留空集 = 全开。
            if (!settings.toastDownloadResult) {
                LinkedHashSet<String> muted = settings.getMutedDownloadToasts();
                muted.add(DownloadToastKind.DOWNLOAD_DONE.name());
                muted.add(DownloadToastKind.DOWNLOAD_FAILED.name());
                muted.add(DownloadToastKind.ARIA2.name());
                muted.add(DownloadToastKind.NOVEL_AUTO_DOWNLOAD.name());
            }
        }
        settings.toastDownloadResult = settings.getMutedDownloadToasts().isEmpty();
    }

    // 插画V3详情页：下载按钮是否在左（true=左下载右收藏，false=左收藏右下载）
    private boolean artworkV3FabDownloadOnLeft = true;

    public boolean isArtworkV3FabDownloadOnLeft() {
        return artworkV3FabDownloadOnLeft;
    }

    public void setArtworkV3FabDownloadOnLeft(boolean artworkV3FabDownloadOnLeft) {
        this.artworkV3FabDownloadOnLeft = artworkV3FabDownloadOnLeft;
    }

    // 插画V3详情页：显示评论预览区块，默认开启；关掉后不产出该区块、不请求评论，跳转评论区按钮随之隐藏
    private boolean artworkV3ShowComments = true;

    public boolean isArtworkV3ShowComments() {
        return artworkV3ShowComments;
    }

    public void setArtworkV3ShowComments(boolean artworkV3ShowComments) {
        this.artworkV3ShowComments = artworkV3ShowComments;
    }

    // 插画V3详情页：悬浮胶囊显示「跳转评论区」按钮（issue #970），默认关闭，设置里手动打开
    private boolean artworkV3ShowCommentJumpFab = false;

    public boolean isArtworkV3ShowCommentJumpFab() {
        return artworkV3ShowCommentJumpFab;
    }

    public void setArtworkV3ShowCommentJumpFab(boolean artworkV3ShowCommentJumpFab) {
        this.artworkV3ShowCommentJumpFab = artworkV3ShowCommentJumpFab;
    }

    // 插画V3详情页：多图作品进页即自动展开剩余页面（issue #1090），默认关闭，设置里手动开启
    private boolean artworkV3AutoExpandMultiPage = false;

    public boolean isArtworkV3AutoExpandMultiPage() {
        return artworkV3AutoExpandMultiPage;
    }

    public void setArtworkV3AutoExpandMultiPage(boolean artworkV3AutoExpandMultiPage) {
        this.artworkV3AutoExpandMultiPage = artworkV3AutoExpandMultiPage;
    }

    // 插画V3详情页 / 二级大图页：悬浮胶囊水平位置（issue #1090）。
    // 0=居中（默认），1=靠左，2=靠右；靠边时收藏心固定在外侧，下载/收藏顺序设置只在居中时生效。
    public static final int ARTWORK_V3_FAB_POSITION_CENTER = 0;
    public static final int ARTWORK_V3_FAB_POSITION_LEFT = 1;
    public static final int ARTWORK_V3_FAB_POSITION_RIGHT = 2;

    private int artworkV3FabPosition = ARTWORK_V3_FAB_POSITION_CENTER;

    public int getArtworkV3FabPosition() {
        if (artworkV3FabPosition < ARTWORK_V3_FAB_POSITION_CENTER ||
                artworkV3FabPosition > ARTWORK_V3_FAB_POSITION_RIGHT) {
            return ARTWORK_V3_FAB_POSITION_CENTER;
        }
        return artworkV3FabPosition;
    }

    public void setArtworkV3FabPosition(int artworkV3FabPosition) {
        this.artworkV3FabPosition = artworkV3FabPosition;
    }

    private String defaultUpscaleModel = "";

    public String getDefaultUpscaleModel() {
        return defaultUpscaleModel == null ? "" : defaultUpscaleModel;
    }

    public void setDefaultUpscaleModel(String defaultUpscaleModel) {
        this.defaultUpscaleModel = defaultUpscaleModel;
    }

    private String defaultRembgModel = "";

    public String getDefaultRembgModel() {
        return defaultRembgModel == null ? "" : defaultRembgModel;
    }

    public void setDefaultRembgModel(String defaultRembgModel) {
        this.defaultRembgModel = defaultRembgModel;
    }

    // "" = 每次询问（弹出格式选择），否则存 ExportFormat 枚举名（Txt / Markdown / Epub / Pdf）
    private String defaultNovelExportFormat = "";

    public String getDefaultNovelExportFormat() {
        return defaultNovelExportFormat == null ? "" : defaultNovelExportFormat;
    }

    public void setDefaultNovelExportFormat(String defaultNovelExportFormat) {
        this.defaultNovelExportFormat = defaultNovelExportFormat;
    }

    // 默认导出格式为 Txt 时，正文含插图自动改用 Epub（默认关闭）
    private boolean defaultNovelExportEpubOnImages = false;

    public boolean isDefaultNovelExportEpubOnImages() {
        return defaultNovelExportEpubOnImages;
    }

    public void setDefaultNovelExportEpubOnImages(boolean defaultNovelExportEpubOnImages) {
        this.defaultNovelExportEpubOnImages = defaultNovelExportEpubOnImages;
    }

    // "" = 原图（当前默认行为），否则存 Params.IMAGE_RESOLUTION_* 值
    private String defaultImageResolution = "";

    public String getDefaultImageResolution() {
        return defaultImageResolution == null ? "" : defaultImageResolution;
    }

    public void setDefaultImageResolution(String defaultImageResolution) {
        this.defaultImageResolution = defaultImageResolution;
    }

    // 试验性:展示公开聊天室新消息的 APP 内 push banner,默认关闭。
    private boolean showChatRoomPushBanner = false;

    public boolean isShowChatRoomPushBanner() {
        return showChatRoomPushBanner;
    }

    public void setShowChatRoomPushBanner(boolean showChatRoomPushBanner) {
        this.showChatRoomPushBanner = showChatRoomPushBanner;
    }

    public float getCustomZoomAddScale() {
        return customZoomAddScale;
    }

    public void setCustomZoomAddScale(float customZoomAddScale) {
        this.customZoomAddScale = customZoomAddScale;
    }
}
