package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

public class StarUserReceiver extends BaseReceiver<UserPreviewsBean> {

    public StarUserReceiver(BaseAdapter<UserPreviewsBean, ?> adapter) {
        super(adapter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Common.showLog("StarUserReceiver 接收到了消息");
        if (intent != null && mAdapter != null) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                int userID = bundle.getInt(Params.USER_ID);
                boolean isLiked = bundle.getBoolean(Params.IS_LIKED);
                mAdapter.setLiked(userID, isLiked);
            }
        }
    }
}
