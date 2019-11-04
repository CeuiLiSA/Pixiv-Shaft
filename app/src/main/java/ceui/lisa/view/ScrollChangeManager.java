package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;

/**
 * 支持切换滚动状态的瀑布流LayoutManager
 */
public class ScrollChangeManager extends StaggeredGridLayoutManager {

    private boolean canScroll = true;

    public ScrollChangeManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ScrollChangeManager(int spanCount, int orientation) {
        super(spanCount, orientation);
    }

    public void setCanScroll(boolean canScroll) {
        this.canScroll = canScroll;
    }

    @Override
    public boolean canScrollVertically() {
        return canScroll;
    }
}
