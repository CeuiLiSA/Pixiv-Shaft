package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class StarReceiver extends BroadcastReceiver {

    private BaseReceiver.CallBack mCallBack;

    public StarReceiver(BaseReceiver.CallBack callBack) {
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
