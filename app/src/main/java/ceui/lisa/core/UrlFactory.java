package ceui.lisa.core;

import android.text.TextUtils;

import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;

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
        if (Dev.is_new_host) {
            String result = "http://" + Dev.GLOABLE_HOST + url.substring(19);
            Common.showLog("compactUrl 00 " + result);
            return result;
        } else {
            Common.showLog("compactUrl 11 " + url);
            return url;
        }
    }
}
