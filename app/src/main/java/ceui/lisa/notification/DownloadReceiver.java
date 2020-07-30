package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import ceui.lisa.database.DownloadEntity;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Params;

public class DownloadReceiver<T> extends BroadcastReceiver {

    private Callback<T> mCallback;
    private int type; // 0是通知FragmentDownloading, 1是通知FragmentDownloadFinish
    public static final int NOTIFY_FRAGMENT_DOWNLOADING = 0;
    public static final int NOTIFY_FRAGMENT_DOWNLOAD_FINISH = 1;

    public DownloadReceiver(Callback<T> callback, int type) {
        mCallback = callback;
        this.type = type;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && context != null) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                if (type == 0) {
                    int index = bundle.getInt(Params.INDEX);
                    if (mCallback != null) {
                        mCallback.doSomething((T) Integer.valueOf(index));
                    }
                } else if (type == 1) {
                    DownloadEntity downloadEntity = (DownloadEntity) bundle.getSerializable(Params.CONTENT);
                    if (mCallback != null) {
                        mCallback.doSomething((T) downloadEntity);
                    }
                }
            }
        }
    }
}
