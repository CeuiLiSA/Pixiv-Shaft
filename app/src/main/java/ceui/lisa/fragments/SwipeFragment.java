package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;

import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

public abstract class SwipeFragment<T extends ViewDataBinding>
        extends BaseLazyFragment<T> implements Swipe {

    @Override
    public RefreshHeader getHeader() {
        return new FalsifyHeader(mContext);
    }

    @Override
    public FalsifyFooter getFooter() {
        return new FalsifyFooter(mContext);
    }

    @Override
    public void init() {
        SmartRefreshLayout layout = getSmartRefreshLayout();
        if (layout != null) {
            layout.setEnableRefresh(true);
            layout.setEnableLoadMore(true);
            layout.setRefreshHeader(getHeader());
            layout.setRefreshFooter(getFooter());
        }
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        init();
    }

    public boolean enableRefresh() {
        return true;
    }

    public boolean enableLoadMore() {
        return true;
    }
}
