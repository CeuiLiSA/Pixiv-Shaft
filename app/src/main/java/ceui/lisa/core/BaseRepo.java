package ceui.lisa.core;

import android.content.Context;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

import ceui.lisa.activities.Shaft;

public class BaseRepo {

    public boolean hasNext(){
        return true;
    }

    public boolean enableRefresh(){
        return true;
    }

    public RefreshHeader getHeader(Context context){
        return new MaterialHeader(context);
    }

    public RefreshFooter getFooter(Context context){
        return new ClassicsFooter(context);
    }

    public boolean showNoDataHint() {
        return true;
    }

    public String token() {
        if (Shaft.sUserModel != null) {
            return Shaft.sUserModel.getResponse().getAccess_token();
        }
        return "";
    }
}
