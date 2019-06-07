package ceui.lisa.utils;

import android.content.Context;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.util.AttributeSet;

public class WrapedManager extends StaggeredGridLayoutManager {



    public void setCanScroll(boolean canScroll) {
        this.canScroll = canScroll;
    }

    private boolean canScroll = true;

    public WrapedManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public WrapedManager(int spanCount, int orientation) {
        super(spanCount, orientation);
    }

    @Override
    public boolean canScrollVertically() {
        return canScroll;
    }
}
