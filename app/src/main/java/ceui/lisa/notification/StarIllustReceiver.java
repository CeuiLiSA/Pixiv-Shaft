package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

public class StarIllustReceiver extends BaseReceiver<IllustsBean> {

    public StarIllustReceiver(BaseAdapter<IllustsBean, ?> adapter) {
        super(adapter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Common.showLog("StarIllustReceiver 接收到了消息");
        if (intent != null && mAdapter != null) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                int illustID = bundle.getInt(Params.ILLUST_ID);
                boolean isLiked = bundle.getBoolean(Params.IS_LIKED);
                mAdapter.setLiked(illustID, isLiked);
            }
        }
    }
}
