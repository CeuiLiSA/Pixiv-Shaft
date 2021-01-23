package ceui.lisa.core;

import android.content.Context;

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
        DeliveryHeader header = new DeliveryHeader(context);
//        header.setPrimaryColors(context.getResources().getColor(R.color.black));
        header.setBackgroundColor(context.getResources().getColor(R.color.fragment_center));
        return header;
    }
}
