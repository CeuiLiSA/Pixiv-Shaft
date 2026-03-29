package ceui.lisa.core;

import android.content.Context;

import com.scwang.smart.refresh.footer.ClassicsFooter;
import com.scwang.smart.refresh.header.MaterialHeader;
import com.scwang.smart.refresh.layout.api.RefreshFooter;
import com.scwang.smart.refresh.layout.api.RefreshHeader;

import ceui.lisa.utils.Common;
import ceui.pixiv.session.SessionManager;

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
        return SessionManager.INSTANCE.getBearerTokenOrEmpty();
    }

    public int currentUserID() {
        return (int) SessionManager.INSTANCE.getLoggedInUid();
    }

    @Override
    public boolean localData() {
        return false;
    }
}
