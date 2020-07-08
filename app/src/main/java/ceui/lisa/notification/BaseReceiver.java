package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.models.Starable;

public abstract class BaseReceiver<Item extends Starable> extends BroadcastReceiver {

    protected BaseAdapter<Item, ?> mAdapter;

    public BaseReceiver(BaseAdapter<Item, ?> adapter) {
        mAdapter = adapter;
    }

    public interface CallBack{
        void onReceive(Context context, Intent intent);
    }
}
