package ceui.lisa.interfaces;

import android.content.Context;
import android.content.Intent;

import java.util.List;

import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.models.IllustsBean;
import ceui.pixiv.ui.bulk.BulkSelectStorage;

/**
 * 旧入口（列表长按 / popup "批量下载"）。
 * 跳到 V3 风格的多选页 BulkSelectV3Fragment，让用户勾选要下哪些。
 */
public interface MultiDownload {

    Context getContext();

    List<IllustsBean> getIllustList();

    default void startDownload() {
        List<IllustsBean> list = getIllustList();
        if (list == null || list.isEmpty()) return;
        BulkSelectStorage.INSTANCE.put(list);
        Intent intent = new Intent(getContext(), TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "批量选择");
        getContext().startActivity(intent);
    }
}
