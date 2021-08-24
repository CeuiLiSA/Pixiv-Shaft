package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class FilterReceiver extends BroadcastReceiver {

    private final BaseReceiver.CallBack mCallBack;

    public FilterReceiver(BaseReceiver.CallBack callBack) {
        mCallBack = callBack;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null
                && mCallBack != null) {
            mCallBack.onReceive(context, intent);
        }
    }
}
