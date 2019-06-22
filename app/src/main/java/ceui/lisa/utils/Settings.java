package ceui.lisa.utils;

public class Settings {

    //瀑布流List点击动画
    private boolean mainListAnimate = true;

    //浏览历史List点击动画
    private boolean viewHistoryAnimate = true;

    //设置页面进场动画
    private boolean settingsAnimate = true;

    public boolean isRelatedIllustNoLimit() {
        return relatedIllustNoLimit;
    }

    public void setRelatedIllustNoLimit(boolean relatedIllustNoLimit) {
        this.relatedIllustNoLimit = relatedIllustNoLimit;
    }

    private boolean relatedIllustNoLimit = true;


    //一级详情FragmentSingleIllust 图片显示原图
    private boolean firstImageSize = false;

    //二级详情FragmentImageDetail 图片显示原图
    private boolean secondImageSize = true;

    //直接下载单个作品所有P
    private boolean directDownloadAllImage = true;

    public boolean isSaveViewHistory() {
        return saveViewHistory;
    }

    public void setSaveViewHistory(boolean saveViewHistory) {
        this.saveViewHistory = saveViewHistory;
    }

    private boolean saveViewHistory = true;

    public boolean isStaggerAnime() {
        return staggerAnime;
    }

    public void setStaggerAnime(boolean staggerAnime) {
        this.staggerAnime = staggerAnime;
    }

    private boolean staggerAnime = true;

    public boolean isGridAnime() {
        return gridAnime;
    }

    public void setGridAnime(boolean gridAnime) {
        this.gridAnime = gridAnime;
    }

    private boolean gridAnime = true;

    public Settings() {
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
}
