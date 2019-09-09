package ceui.lisa.view;

import android.content.Context;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import android.util.AttributeSet;

/**
 * 支持切换滚动状态的瀑布流LayoutManager
 */
public class ScrollChangeManager extends StaggeredGridLayoutManager {

    public void setCanScroll(boolean canScroll) {
        this.canScroll = canScroll;
    }

    private boolean canScroll = true;

    public ScrollChangeManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ScrollChangeManager(int spanCount, int orientation) {
        super(spanCount, orientation);
    }

    @Override
    public boolean canScrollVertically() {
        return canScroll;
    }
}
