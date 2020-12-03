package ceui.lisa.core;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class Container {

    private HashMap<String, IDWithList<IllustsBean>> pages = new HashMap<>();

    /**
     * 用 HashMap 存储，app杀掉之后就没有了
     *
     * @param pageData 一个插画列表
     */
    public void addPageToMap(IDWithList<IllustsBean> pageData) {
        if (pageData == null) {
            return;
        }

        if (TextUtils.isEmpty(pageData.getUUID())) {
            return;
        }

        if (pages == null) {
            pages = new HashMap<>();
        }

        pages.put(pageData.getUUID(), pageData);
        Common.showLog("Container addPage " + pageData.getUUID());
    }

    public IDWithList<IllustsBean> getPage(String uuid) {
        Common.showLog("Container getPage " + uuid);
        if (TextUtils.isEmpty(uuid)) {
            return null;
        }

        if (pages == null || pages.size() == 0) {
            return null;
        }

        return pages.get(uuid);
    }

    public List<PageData> getAll() {
        List<PageData> result = new ArrayList<>();
        if (pages == null || pages.size() == 0) {
            return result;
        }

        for (IDWithList<IllustsBean> value : pages.values()) {
            result.add((PageData) value);
        }
        return result;
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

    private Container() {
    }

    private static class SingleTonHolder {
        private static final Container INSTANCE = new Container();
    }

    public static Container get() {
        return SingleTonHolder.INSTANCE;
    }
}
