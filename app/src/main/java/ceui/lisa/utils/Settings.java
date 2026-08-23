package ceui.lisa.utils;

import android.text.TextUtils;

import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.PathUtils;
import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.helper.ThemeHelper;
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

    //屏蔽，不显示已收藏的作品，默认不屏蔽
    private boolean deleteStarIllust = false;

    //排行榜过滤已收藏的作品，默认过滤
    private boolean filterRankBookmarked = true;

    //屏蔽，不显示AI创作的作品，默认不屏蔽
    private boolean deleteAIIllust = false;

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

    //小说卡片是否显示标签
    private boolean showNovelCardTags = true;

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

    private boolean r18FilterDefaultEnable = false; // 默认开启R18内容过滤

    private boolean toastDownloadResult = true; // 默认提示下载结果

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

    /** 平板大屏双栏（Activity Embedding，#931）。默认关闭，只有平板打开后才注册分栏规则。 */
    private boolean tabletSplitScreen = false;

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

    /** 已完成 tab 的列表展示模式（0=横向列表，1=网格 2 列，2=紧凑缩图 4 列）。1 = 旧默认。 */
    private int doneListLayoutMode = 1;

    private boolean illustDetailKeepScreenOn = false; //插画二级详情保持屏幕常亮

    // 看图时为状态栏(刘海/挖孔)留出顶部空间，避免多图/竖图铺满顶部被遮挡（issue #724）。默认关闭，保持原沉浸式铺满。
    private boolean keepStatusBarWhenViewImage = false;

    // 收藏夹过滤已失效作品（已删除/不可见），默认不过滤
    private boolean filterInvalidBookmarks = false;

    // 同义词词典功能总开关（issue #904），默认关闭。
    // 关闭时所有相关 UI（详情页匹配框/长按菜单项/管理页入口/自动导入/自动勾选）完全隐藏
    private boolean synonymDictEnabled = false;

    // 动图(ugoira) RIFE AI 补帧，默认关闭。开启且补帧模型已下载时,播放引擎在编码前
    // 对帧序列做 2x 插帧,帧率翻倍;模型未下载则静默回落原始帧率
    private boolean ugoiraRifeEnable = false;

    // 详情页动图(ugoira)自动播放,默认开启(行为不变)。关闭后进详情不自动下载/播放,
    // 图片中间显示「开始播放(下载)」按钮;已缓存或左右切回也不自动播,点按钮才开始。
    private boolean autoPlayUgoira = true;

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

    public boolean isDeleteAIIllust() {
        return deleteAIIllust;
    }

    public void setDeleteAIIllust(boolean b) {
        deleteAIIllust = b;
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

    public boolean isTabletSplitScreen() {
        return tabletSplitScreen;
    }

    public void setTabletSplitScreen(boolean enable) {
        this.tabletSplitScreen = enable;
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

    public boolean isShowNovelCardTags() {
        return showNovelCardTags;
    }

    public void setShowNovelCardTags(boolean showNovelCardTags) {
        this.showNovelCardTags = showNovelCardTags;
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

    public boolean isUgoiraRifeEnable() {
        return ugoiraRifeEnable;
    }

    public void setUgoiraRifeEnable(boolean ugoiraRifeEnable) {
        this.ugoiraRifeEnable = ugoiraRifeEnable;
    }

    public boolean isAutoPlayUgoira() {
        return autoPlayUgoira;
    }

    public void setAutoPlayUgoira(boolean autoPlayUgoira) {
        this.autoPlayUgoira = autoPlayUgoira;
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

    public boolean isUseCustomLongPressReset() {
        return useCustomLongPressReset;
    }

    public void setUseCustomLongPressReset(boolean useCustomLongPressReset) {
        this.useCustomLongPressReset = useCustomLongPressReset;
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

    // 插画V3详情页：下载按钮是否在左（true=左下载右收藏，false=左收藏右下载）
    private boolean artworkV3FabDownloadOnLeft = true;

    public boolean isArtworkV3FabDownloadOnLeft() {
        return artworkV3FabDownloadOnLeft;
    }

    public void setArtworkV3FabDownloadOnLeft(boolean artworkV3FabDownloadOnLeft) {
        this.artworkV3FabDownloadOnLeft = artworkV3FabDownloadOnLeft;
    }

    // 插画V3详情页：悬浮胶囊显示「跳转评论区」按钮（issue #970），默认关闭，设置里手动打开
    private boolean artworkV3ShowCommentJumpFab = false;

    public boolean isArtworkV3ShowCommentJumpFab() {
        return artworkV3ShowCommentJumpFab;
    }

    public void setArtworkV3ShowCommentJumpFab(boolean artworkV3ShowCommentJumpFab) {
        this.artworkV3ShowCommentJumpFab = artworkV3ShowCommentJumpFab;
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

    // "" = 原图（当前默认行为），否则存 Params.IMAGE_RESOLUTION_* 值
    private String defaultImageResolution = "";

    public String getDefaultImageResolution() {
        return defaultImageResolution == null ? "" : defaultImageResolution;
    }

    public void setDefaultImageResolution(String defaultImageResolution) {
        this.defaultImageResolution = defaultImageResolution;
    }

    // 试验性:首页侧边栏展示「聊天室」入口,默认关闭
    private boolean showChatRoomEntry = false;

    // 试验性:展示公开聊天室新消息的 APP 内 push banner,默认关闭(仅在 showChatRoomEntry 开启时有意义)
    private boolean showChatRoomPushBanner = false;

    // 试验性:首页侧边栏展示「广场」入口,默认关闭
    private boolean showPlazaEntry = false;

    public boolean isShowChatRoomEntry() {
        return showChatRoomEntry;
    }

    public void setShowChatRoomEntry(boolean showChatRoomEntry) {
        this.showChatRoomEntry = showChatRoomEntry;
    }

    public boolean isShowChatRoomPushBanner() {
        return showChatRoomPushBanner;
    }

    public void setShowChatRoomPushBanner(boolean showChatRoomPushBanner) {
        this.showChatRoomPushBanner = showChatRoomPushBanner;
    }

    public boolean isShowPlazaEntry() {
        return showPlazaEntry;
    }

    public void setShowPlazaEntry(boolean showPlazaEntry) {
        this.showPlazaEntry = showPlazaEntry;
    }

    public float getCustomZoomAddScale() {
        return customZoomAddScale;
    }

    public void setCustomZoomAddScale(float customZoomAddScale) {
        this.customZoomAddScale = customZoomAddScale;
    }
}
