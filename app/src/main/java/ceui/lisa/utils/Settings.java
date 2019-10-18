package ceui.lisa.utils;

import android.text.TextUtils;

public class Settings {

    //只包含1P图片的下载路径
    public static final String FILE_PATH_SINGLE = "/storage/emulated/0/Shaft/SingleImages";

    //包含多P文件的下载路径
    public static final String FILE_PATH_META = "/storage/emulated/0/Shaft/MetaImages";

    //下载的GIF 压缩包存放在这里
    public static final String FILE_GIF_PATH = "/storage/emulated/0/Shaft/gif";

    //下载的GIF 压缩包解压之后的结果存放在这里
    public static final String FILE_GIF_CHILD_PATH = "/storage/emulated/0/Shaft/gifUnzip";

    //已制作好的GIF存放在这里
    public static final String FILE_GIF_RESULT_PATH = "/storage/emulated/0/Shaft/gifGenerate";

    //WEB下载
    public static final String WEB_DOWNLOAD_PATH = "/storage/emulated/0/Shaft/Web";


    //瀑布流List点击动画
    private boolean mainListAnimate = true;

    //浏览历史List点击动画
    private boolean viewHistoryAnimate = true;

    //设置页面进场动画
    private boolean settingsAnimate = true;

    //是否自动添加DNS，true开启直连  false自行代理
    private boolean autoFuckChina = true;

    private boolean relatedIllustNoLimit = true;

    //一级详情FragmentSingleIllust 图片显示原图
    private boolean firstImageSize = false;

    //二级详情FragmentImageDetail 图片显示原图
    private boolean secondImageSize = true;

    //直接下载单个作品所有P
    private boolean directDownloadAllImage = true;

    private boolean saveViewHistory = true;

    private boolean staggerAnime = true;

    private boolean gridAnime = true;

    private String illustPath = "";

    private String gifResultPath = "";

    private String gifZipPath = "";

    private String gifUnzipPath = "";

    private String webDownloadPath = "";

    //收藏量筛选搜索结果
    private String searchFilter = "";

    public Settings() {
    }

    public boolean isGridAnime() {
        return gridAnime;
    }

    public void setGridAnime(boolean gridAnime) {
        this.gridAnime = gridAnime;
    }

    public boolean isSaveViewHistory() {
        return saveViewHistory;
    }

    public void setSaveViewHistory(boolean saveViewHistory) {
        this.saveViewHistory = saveViewHistory;
    }

    public String getSearchFilter() {
        return TextUtils.isEmpty(searchFilter) ? " 无限制" : searchFilter;
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

    public boolean isStaggerAnime() {
        return staggerAnime;
    }

    public void setStaggerAnime(boolean staggerAnime) {
        this.staggerAnime = staggerAnime;
    }

    public boolean isMainListAnimate() {
        return mainListAnimate;
    }

    public void setMainListAnimate(boolean mainListAnimate) {
        this.mainListAnimate = mainListAnimate;
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

    public boolean isFirstImageSize() {
        return firstImageSize;
    }

    public void setFirstImageSize(boolean firstImageSize) {
        this.firstImageSize = firstImageSize;
    }

    public boolean isSecondImageSize() {
        return secondImageSize;
    }

    public void setSecondImageSize(boolean secondImageSize) {
        this.secondImageSize = secondImageSize;
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
}
