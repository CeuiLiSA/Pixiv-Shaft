package ceui.lisa.interfaces;

import android.content.Context;
import android.content.Intent;

import java.util.List;

import ceui.lisa.activities.TemplateActivity;
import ceui.pixiv.api.model.Illust;
import ceui.pixiv.ui.bulk.BulkSelectHandoff;
import ceui.pixiv.ui.bulk.BulkSelectHandoffKt;
import ceui.pixiv.ui.navigation.TemplateRoute;

/**
 * 旧入口（列表长按 / popup "批量下载"）。
 * 跳到 V3 风格的多选页 BulkSelectV3Fragment，让用户勾选要下哪些。
 */
public interface MultiDownload {

    Context getContext();

    List<Illust> getIllustList();

    default void startDownload() {
        List<Illust> list = getIllustList();
        if (list == null || list.isEmpty()) return;
        String key = BulkSelectHandoffKt.IllustBulkSelectHandoff.put(list);
        Intent intent = new Intent(getContext(), TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.BULK_SELECT.key);
        intent.putExtra(BulkSelectHandoff.ARG_HANDOFF_KEY, key);
        getContext().startActivity(intent);
    }
}
