package ceui.lisa.core;

import android.content.Context;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

import ceui.lisa.activities.Shaft;

public class BaseRepo implements DataView{

    @Override
    public boolean hasNext() {
        return true;
    }

    @Override
    public boolean enableRefresh() {
        return true;
    }

    @Override
    public RefreshHeader getHeader(Context context) {
        return new MaterialHeader(context);
    }

    @Override
    public RefreshFooter getFooter(Context context) {
        return new ClassicsFooter(context);
    }

    @Override
    public boolean showNoDataHint() {
        return true;
    }

    @Override
    public String token() {
        if (Shaft.sUserModel != null) {
            return Shaft.sUserModel.getResponse().getAccess_token();
        }
        return "";
    }
}
