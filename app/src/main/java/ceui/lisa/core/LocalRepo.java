package ceui.lisa.core;

import android.content.Context;

import com.scwang.smart.refresh.layout.api.RefreshHeader;

import ceui.lisa.view.MyDeliveryHeader;

public abstract class LocalRepo<T> extends BaseRepo {

    public abstract T first();

    public abstract T next();

    @Override
    public boolean hasNext() {
        return false;
    }

    public boolean enableRefresh() {
        return true;
    }

    @Override
    public RefreshHeader getHeader(Context context) {
        return new MyDeliveryHeader(context);
    }
}
