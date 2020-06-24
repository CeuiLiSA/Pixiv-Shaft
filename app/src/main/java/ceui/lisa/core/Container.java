package ceui.lisa.core;

import android.text.TextUtils;

import java.util.HashMap;

import ceui.lisa.utils.Common;

public class Container {

    private HashMap<String, PageData> pages = new HashMap<>();

    public void addPage(PageData pageData) {
        if (pageData == null) {
            return;
        }

        if (TextUtils.isEmpty(pageData.getUuid())) {
            return;
        }

        if (pages == null) {
            pages = new HashMap<>();
        }

        pages.put(pageData.getUuid(), pageData);
        Common.showLog("Container addPage " + pageData.getUuid());
    }

    public PageData getPage(String uuid) {
        Common.showLog("Container getPage " + uuid);
        if (TextUtils.isEmpty(uuid)) {
            return null;
        }

        if (pages == null || pages.size() == 0) {
            return null;
        }

        return pages.get(uuid);
    }

    public void clear() {
        Common.showLog("Container clear ");
        if (pages == null) {
            pages = new HashMap<>();
            return;
        }
        if (pages.size() != 0) {
            pages.clear();
        }
    }

    private Container(){}

    private static class SingleTonHolder{
        private static Container INSTANCE = new Container();
    }

    public static Container get(){
        return SingleTonHolder.INSTANCE;
    }
}
