package ceui.lisa.core;

import android.text.TextUtils;

import ceui.lisa.activities.Shaft;
import ceui.lisa.feature.HostManager;
import ceui.lisa.utils.Common;

public class UrlFactory {

    public static final String HOST_OLD = "i.pximg.net";
    public static final String HOST_NEW = "i.pixiv.cat";

    public static String invoke(String before) {
        if (Shaft.sSettings.isUsePixivCat() && !TextUtils.isEmpty(before) && before.contains(HOST_OLD)) {
            String finalUrl = before.replace(HOST_OLD, HOST_NEW);
            Common.showLog("use Pixiv Cat " + finalUrl);
            return finalUrl;
        } else {
            String result = compactUrl(before);
            Common.showLog("use original " + result);
            return result;
        }
    }

    public static String compactUrl(String url) {
        String result = "http://" + HostManager.get().getHost() + url.substring(19);
        Common.showLog("compactUrl 00 " + result);
        return result;
    }
}
