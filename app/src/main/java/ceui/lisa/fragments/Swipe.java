package ceui.lisa.fragments;

import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

public interface Swipe {

    SmartRefreshLayout getSmartRefreshLayout();

    RefreshHeader getHeader();

    FalsifyFooter getFooter();

    void init();
}
