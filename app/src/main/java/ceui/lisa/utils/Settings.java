package ceui.lisa.utils;

import android.text.TextUtils;

import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.PathUtils;

import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.helper.ThemeHelper;

public class Settings {

    public static final String[] ALL_LANGUAGE = new String[]{"简体中文", "日本語", "English", "繁體中文", "русский", "한국어"};

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

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int lineCount) {
        this.lineCount = lineCount;
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

    //屏蔽，不显示AI创作的作品，默认不屏蔽
    private boolean deleteAIIllust = false;

    //是否自动添加DNS，true开启直连  false自行代理
    private boolean autoFuckChina = false;

    private boolean relatedIllustNoLimit = true;

    //使用pixiv cat 代理 展示图片
    private boolean usePixivCat = false;

    //缩略图图片显示大图
    private boolean showLargeThumbnailImage = false;

    //一级详情FragmentIllust 图片显示原图
    private boolean showOriginalPreviewImage = false;

    //二级详情FragmentImageDetail 图片显示原图
    private boolean showOriginalImage = false;

    //是否显示开屏 dialog
    private boolean showPixivDialog = true;

    //默认私人收藏
    private boolean privateStar = false;

    //列表页面是否显示收藏按钮
    private boolean showLikeButton = true;

    //直接下载单个作品所有P
    private boolean directDownloadAllImage = true;

    private boolean saveViewHistory = true;

    private boolean r18DivideSave = false;

    //AI作品下载至单独的目录
    private boolean AIDivideSave = false;


    //在我的收藏列表，隐藏收藏按钮，默认显示
    private boolean hideStarButtonAtMyCollection = false;

    //按标签收藏时全选标签。默认不全选
    private boolean starWithTagSelectAll = false;

    //单P作品的文件名是否带P0
    private boolean hasP0 = false;

    //作品详情使用新页面
    private boolean useFragmentIllust = true;

    //个人中心使用新页面
    private boolean useNewUserPage = true;

    private String illustPath = "";

    private String novelPath = "";

    private String gifResultPath = "";

    private String gifZipPath = "";

    private String gifUnzipPath = "";

    private String webDownloadPath = "";

    private int novelHolderColor = 0;

    private int novelHolderTextColor = 0;

    private int bottomBarOrder = 0;

    private boolean reverseDialogNeverShowAgain = false;

    private String appLanguage = "";

    private String fileNameJson = "";

    private String rootPathUri = "";

    private int downloadWay = 0; //0传统模式，保存到Pictures目录下。    1 SAF模式保存到自选目录下

    private boolean filterComment = false; // 过滤垃圾评论，默认不开启

    private int transformerType = 5; // 二级详情转场动画，默认是3D盒子

    private boolean showRelatedWhenStar = true; // 收藏作品时展示关联作品

    private boolean globalSwipeBack = true; // 全局滑动返回

    private boolean illustLongPressDownload = false; // 插画详情长按下载

    private boolean illustDetailShowNavbar = true; // 插画二级详情显示导航栏

    private int saveForSeparateAuthorStatus = 0; // 不同作者单独保存

    private boolean autoPostLikeWhenDownload = false; // 下载时自动收藏

    private boolean r18FilterDefaultEnable = false; // 默认开启R18内容过滤

    private transient boolean r18FilterTempEnableInitialed = false;
    private transient boolean r18FilterTempEnable = false; // 临时开启R18内容过滤

    private String searchDefaultSortType = ""; // 搜索结果默认排序方式

    private String navigationInitPosition = NavigationLocationHelper.TUIJIAN; // 主页底部导航栏初始化位置

//    private boolean isDownloadOnlyUseWiFi = false; // 仅通过 Wifi 下载

    private int downloadLimitType = 0; // 下载限制类型 0:无限制 1:仅Wifi下自动下载 2:不自动下载

    private boolean illustDetailKeepScreenOn = false; //插画二级详情保持屏幕常亮

    public String getAppLanguage() {
        if(!TextUtils.isEmpty(appLanguage)){
            return appLanguage;
        } else {
            return ALL_LANGUAGE[0];
        }
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

    public String getSearchFilter() {
        return TextUtils.isEmpty(searchFilter) ? "" : searchFilter;
    }

    public boolean isUsePixivCat() {
        return usePixivCat;
    }

    public void setUsePixivCat(boolean usePixivCat) {
        this.usePixivCat = usePixivCat;
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

    public boolean isAutoFuckChina() {
        return autoFuckChina;
    }

    public void setAutoFuckChina(boolean autoFuckChina) {
        this.autoFuckChina = autoFuckChina;
    }

    public boolean isMainViewR18() {
        return mainViewR18;
    }

    public void setMainViewR18(boolean mainViewR18) {
        this.mainViewR18 = mainViewR18;
    }

    public boolean isUseFragmentIllust() {
        return useFragmentIllust;
    }

    public void setUseFragmentIllust(boolean useFragmentIllust) {
        this.useFragmentIllust = useFragmentIllust;
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

    public boolean isShowOriginalImage() {
        return showOriginalImage;
    }

    public void setShowOriginalImage(boolean showOriginalImage) {
        this.showOriginalImage = showOriginalImage;
    }

    public boolean isDirectDownloadAllImage() {
        return directDownloadAllImage;
    }

    public void setDirectDownloadAllImage(boolean directDownloadAllImage) {
        this.directDownloadAllImage = directDownloadAllImage;
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

    public int getBottomBarOrder() {
        return bottomBarOrder;
    }

    public void setBottomBarOrder(int bottomBarOrder) {
        this.bottomBarOrder = bottomBarOrder;
    }

    public boolean isUseNewUserPage() {
        return useNewUserPage;
    }

    public void setUseNewUserPage(boolean useNewUserPage) {
        this.useNewUserPage = useNewUserPage;
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

    public boolean isGlobalSwipeBack() {
        return globalSwipeBack;
    }

    public void setGlobalSwipeBack(boolean globalSwipeBack) {
        this.globalSwipeBack = globalSwipeBack;
    }

    public boolean isIllustLongPressDownload() {
        return illustLongPressDownload;
    }

    public void setIllustLongPressDownload(boolean illustLongPressDownload) {
        this.illustLongPressDownload = illustLongPressDownload;
    }

    public boolean isIllustDetailShowNavbar() {
        return illustDetailShowNavbar;
    }

    public void setIllustDetailShowNavbar(boolean illustDetailShowNavbar) {
        this.illustDetailShowNavbar = illustDetailShowNavbar;
    }

    public boolean isAutoPostLikeWhenDownload() {
        return autoPostLikeWhenDownload;
    }

    public void setAutoPostLikeWhenDownload(boolean autoPostLikeWhenDownload) {
        this.autoPostLikeWhenDownload = autoPostLikeWhenDownload;
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
        return TextUtils.isEmpty(searchDefaultSortType) ? PixivSearchParamUtil.POPULAR_SORT_VALUE : searchDefaultSortType;
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

    public boolean isShowLargeThumbnailImage() {
        return showLargeThumbnailImage;
    }

    public void setShowLargeThumbnailImage(boolean showLargeThumbnailImage) {
        this.showLargeThumbnailImage = showLargeThumbnailImage;
    }

    public boolean isIllustDetailKeepScreenOn() {
        return illustDetailKeepScreenOn;
    }

    public void setIllustDetailKeepScreenOn(boolean illustDetailKeepScreenOn) {
        this.illustDetailKeepScreenOn = illustDetailKeepScreenOn;
    }
}
