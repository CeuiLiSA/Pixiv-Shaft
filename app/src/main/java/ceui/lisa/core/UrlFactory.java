package ceui.lisa.core;

import android.text.TextUtils;

import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;

public class UrlFactory {

    private static final String HOST_OLD = "i.pximg.net";
    private static final String HOST_NEW = "i.pixiv.cat";

    public static String invoke(String before) {
        if (Shaft.sSettings.isUsePixivCat() && !TextUtils.isEmpty(before) && before.contains(HOST_OLD)) {
            String finalUrl = before.replace(HOST_OLD, HOST_NEW);
            Common.showLog("use Pixiv Cat " + finalUrl);
            return finalUrl;
        } else {
            Common.showLog("use original " + before);
            return before;
        }
    }
}
