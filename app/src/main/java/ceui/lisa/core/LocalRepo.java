package ceui.lisa.core;

import android.content.Context;
import android.util.AttributeSet;

import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshHeader;

import ceui.lisa.R;

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
        return new DeliveryHeader(context);
    }
}
