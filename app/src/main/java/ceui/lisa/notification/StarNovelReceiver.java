package ceui.lisa.notification;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

public class StarNovelReceiver extends BaseReceiver<NovelBean> {

    public StarNovelReceiver(BaseAdapter<NovelBean, ?> adapter) {
        super(adapter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Common.showLog("StartNovelReceiver 接收到了消息");
        if (intent != null && mAdapter != null) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                int userID = bundle.getInt(Params.NOVEL_ID);
                boolean isLiked = bundle.getBoolean(Params.IS_LIKED);
                mAdapter.setLiked(userID, isLiked);
            }
        }
    }
}
