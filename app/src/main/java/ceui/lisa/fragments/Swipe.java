package ceui.lisa.fragments;


import com.scwang.smart.refresh.header.FalsifyFooter;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;
import com.scwang.smart.refresh.layout.api.RefreshHeader;

public interface Swipe {

    SmartRefreshLayout getSmartRefreshLayout();

    RefreshHeader getHeader();

    FalsifyFooter getFooter();

    void init();
}
