package ceui.lisa.core;

import android.net.Uri;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import java.io.Serializable;
import java.util.UUID;

import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class DownloadItem implements Serializable {

    private String name;
    private String url;
    private String showUrl;
    private String uuid;
    private IllustsBean illust;

    public DownloadItem(IllustsBean illustsBean) {
        this.illust = illustsBean;
        this.uuid = UUID.randomUUID().toString();
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

    public IllustsBean getIllust() {
        return illust;
    }

    public void setUrl(String url) {
        Common.showLog("DownloadItem 准备下载：" + url);
        this.url = url;
    }

    public boolean isSame(DownloadItem next) {
        return next != null &&
                TextUtils.equals(name, next.name) &&
                TextUtils.equals(url, next.url);
    }
}
