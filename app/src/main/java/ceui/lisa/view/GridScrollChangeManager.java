package ceui.lisa.view;

import android.content.Context;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.util.AttributeSet;

/**
 * 支持切换滚动状态的GridLayoutManager
 */
public class GridScrollChangeManager extends GridLayoutManager {

    public GridScrollChangeManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public GridScrollChangeManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    public GridScrollChangeManager(Context context, int spanCount, int orientation, boolean reverseLayout) {
        super(context, spanCount, orientation, reverseLayout);
    }

    public void setCanScroll(boolean canScroll) {
        this.canScroll = canScroll;
    }

    private boolean canScroll = true;



    @Override
    public boolean canScrollVertically() {
        return canScroll;
    }
}
