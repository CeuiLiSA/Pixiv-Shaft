package ceui.lisa.core;

import android.content.Context;
import android.text.TextUtils;

import java.util.HashMap;

import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UUIDEntity;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class Container {

    private HashMap<String, IDWithList<IllustsBean>> pages = new HashMap<>();

    public void addPage(PageData pageData) {
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

    public void addPage(Context context, UUIDEntity uuidEntity) {
        if (uuidEntity == null || context == null) {
            return;
        }

        if (TextUtils.isEmpty(uuidEntity.getUuid())) {
            return;
        }

        AppDatabase.getAppDatabase(context).searchDao().insertListWithUUID(uuidEntity);
        Common.showLog("Container addPage " + uuidEntity.getUuid());
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

    public IDWithList<IllustsBean> getPage(Context context, String uuid) {
        Common.showLog("Container getPage " + uuid);
        if (TextUtils.isEmpty(uuid) || context == null) {
            return null;
        }

        return AppDatabase.getAppDatabase(context).searchDao().getListByUUID(uuid);
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
