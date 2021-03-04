package ceui.lisa.helper;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.lisa.utils.DensityUtil;

public class StaggeredtManager extends StaggeredGridLayoutManager {

    public StaggeredtManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public StaggeredtManager(int spanCount, int orientation) {
        super(spanCount, orientation);
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        LinearSmoothScroller scroller = new LinearSmoothScroller(recyclerView.getContext()){
            @Override
            protected int getVerticalSnapPreference() {
                return SNAP_TO_START;
            }

            @Override
            protected void onTargetFound(View targetView, RecyclerView.State state, Action action) {
                final int dx = calculateDxToMakeVisible(targetView, getHorizontalSnapPreference());
                final int dy = calculateDyToMakeVisible(targetView, getVerticalSnapPreference());
                final int distance = (int) Math.sqrt(dx * dx + dy * dy);
                final int time = calculateTimeForDeceleration(distance);
                if (time > 0) {
                    action.update(-dx, -dy, time, mDecelerateInterpolator);
                }
            }
        };
        scroller.setTargetPosition(position);
        startSmoothScroll(scroller);
    }
}
