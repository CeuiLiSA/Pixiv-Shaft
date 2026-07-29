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

    //缩略图图片显示大图
    private boolean showLargeThumbnailImage = false;

    //一级详情FragmentIllust 图片显示原图
    private boolean showOriginalPreviewImage = false;


    //是否显示开屏 dialog
    private boolean showPixivDialog = true;

    //默认私人收藏
    private boolean privateStar = false;

    //列表页面是否显示收藏按钮
    private boolean showLikeButton = true;

    //小说卡片是否显示标签
    private boolean showNovelCardTags = true;

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

    private transient boolean r18FilterTempEnableInitialed = false;
    private transient boolean r18FilterTempEnable = false; // 临时开启R18内容过滤

    private String searchDefaultSortType = ""; // 搜索结果默认排序方式

    private String navigationInitPosition = NavigationLocationHelper.TUIJIAN; // 主页底部导航栏初始化位置

//    private boolean isDownloadOnlyUseWiFi = false; // 仅通过 Wifi 下载

    private int downloadLimitType = 0; // 下载限制类型 0:无限制 1:仅Wifi下自动下载 2:不自动下载

    /** 同时下载的最大任务数（1-5）。1 = 严格串行（旧默认行为）。 */
    private int maxConcurrentDownloads = 1;

    // ===== aria2 远程下载（#692）：启用后图片下载任务通过 JSON-RPC 发给远端 aria2（如 NAS），不在本地落盘 =====
    private boolean aria2Enabled = false;
    /** aria2 JSON-RPC 端点，如 http://192.168.1.5:6800/jsonrpc */
    private String aria2RpcUrl = "";
    /** aria2 RPC 密钥（--rpc-secret），可空 */
    private String aria2RpcSecret = "";
    /** 远端下载目录（aria2 的 dir 选项），可空 = 使用 aria2 全局配置 */
    private String aria2RemoteDir = "";

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

    public String getNavigationInitPosition() {
        return navigationInitPosition;
    }

    public void setNavigationInitPosition(String navigationInitPosition) {
        this.navigationInitPosition = navigationInitPosition;
    }

    public String getSearchDefaultSortType() {
        // 默认排序：date_desc（从新到旧）—— pixiv 在 popular-preview 端点上 lang 过滤效果很弱，
        // 默认走 date_desc（searchIllust/searchNovel 端点）能让语种筛选可见地生效。
        return TextUtils.isEmpty(searchDefaultSortType) ? PixivSearchParamUtil.SORT_TYPE_VALUE[0] : searchDefaultSortType;
    }

    public void setSearchDefaultSortType(String searchDefaultSortType) {
        this.searchDefaultSortType = searchDefaultSortType;
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

    public boolean isAutoRefreshHomeFeed() {
        return autoRefreshHomeFeed;
    }

    public void setAutoRefreshHomeFeed(boolean autoRefreshHomeFeed) {
        this.autoRefreshHomeFeed = autoRefreshHomeFeed;
    }

    // 插画二级详情：双击放大模式（false=ZoomImage 默认双击缩放，true=自定义增量双击+长按归位 PR#900）
    private boolean useCustomDoubleTapZoom = false;

    private float customZoomAddScale = 1.8f;

    private boolean useCustomLongPressReset = false;

    private boolean useThreeLevelZoo = false;

    public boolean isUseCustomDoubleTapZoom() {
        return useCustomDoubleTapZoom;
    }

    public boolean isUseCustomLongPressReset() {
        return useCustomLongPressReset;
    }

    public boolean isUseThreeLevelZoo() {
        return useThreeLevelZoo;
    }

    public void setUseCustomDoubleTapZoom(boolean useCustomDoubleTapZoom) {
        this.useCustomDoubleTapZoom = useCustomDoubleTapZoom;
    }

    public void setUseCustomLongPressReset(boolean useCustomLongPressReset) {
        this.useCustomLongPressReset = useCustomLongPressReset;
    }

    public void setUseThreeLevelZoo(boolean useThreeLevelZoo) {
        this.useThreeLevelZoo = useThreeLevelZoo;
    }

    // 插画V3详情页：下载按钮是否在左（true=左下载右收藏，false=左收藏右下载）
    private boolean artworkV3FabDownloadOnLeft = true;

    public boolean isArtworkV3FabDownloadOnLeft() {
        return artworkV3FabDownloadOnLeft;
    }

    public void setArtworkV3FabDownloadOnLeft(boolean artworkV3FabDownloadOnLeft) {
        this.artworkV3FabDownloadOnLeft = artworkV3FabDownloadOnLeft;
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
