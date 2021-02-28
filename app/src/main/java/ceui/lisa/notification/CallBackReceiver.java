package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CallBackReceiver extends BroadcastReceiver {

    private BaseReceiver.CallBack mCallBack;

    public CallBackReceiver(BaseReceiver.CallBack callBack) {
        mCallBack = callBack;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (mCallBack != null) {
            mCallBack.onReceive(context, intent);
        }
    }
}
