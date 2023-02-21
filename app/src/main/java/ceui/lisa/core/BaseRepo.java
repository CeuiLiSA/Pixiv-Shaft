package ceui.lisa.core;

import android.content.Context;

import com.scwang.smart.refresh.footer.ClassicsFooter;
import com.scwang.smart.refresh.header.MaterialHeader;
import com.scwang.smart.refresh.layout.api.RefreshFooter;
import com.scwang.smart.refresh.layout.api.RefreshHeader;

import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;

public class BaseRepo implements DataView{

    public BaseRepo() {
        Common.showLog("BaseRepo " + getClass().getSimpleName() + " newInstance");
    }

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
        try {
            if (Shaft.sUserModel != null) {
                return Shaft.sUserModel.getAccess_token();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public int currentUserID() {
        try {
            if (Shaft.sUserModel != null) {
                return Shaft.sUserModel.getUser().getId();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public boolean localData() {
        return false;
    }
}
