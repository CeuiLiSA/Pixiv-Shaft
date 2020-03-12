package ceui.lisa.interfaces;

import android.content.Context;

import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.header.ClassicsHeader;

public abstract class DataControl<T> extends BaseCtrl{

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
        return new ClassicsHeader(context);
    }
}
