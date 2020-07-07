package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;


public class ViewOffsetBehavior<V extends View> extends CoordinatorLayout.Behavior<V> {

    private ViewOffsetHelper viewOffsetHelper;

    private int tempTopBottomOffset = 0;
    private int tempLeftRightOffset = 0;

    public ViewOffsetBehavior() {}

    public ViewOffsetBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onLayoutChild(
            @NonNull CoordinatorLayout parent, @NonNull V child, int layoutDirection) {
        // First let lay the child out
        layoutChild(parent, child, layoutDirection);

        if (viewOffsetHelper == null) {
            viewOffsetHelper = new ViewOffsetHelper(child);
        }
        viewOffsetHelper.onViewLayout();
        viewOffsetHelper.applyOffsets();

        if (tempTopBottomOffset != 0) {
            viewOffsetHelper.setTopAndBottomOffset(tempTopBottomOffset);
            tempTopBottomOffset = 0;
        }
        if (tempLeftRightOffset != 0) {
            viewOffsetHelper.setLeftAndRightOffset(tempLeftRightOffset);
            tempLeftRightOffset = 0;
        }

        return true;
    }

    protected void layoutChild(
            @NonNull CoordinatorLayout parent, @NonNull V child, int layoutDirection) {
        // Let the parent lay it out by default
        parent.onLayoutChild(child, layoutDirection);
    }

    public boolean setTopAndBottomOffset(int offset) {
        if (viewOffsetHelper != null) {
            return viewOffsetHelper.setTopAndBottomOffset(offset);
        } else {
            tempTopBottomOffset = offset;
        }
        return false;
    }

    public boolean setLeftAndRightOffset(int offset) {
        if (viewOffsetHelper != null) {
            return viewOffsetHelper.setLeftAndRightOffset(offset);
        } else {
            tempLeftRightOffset = offset;
        }
        return false;
    }

    public int getTopAndBottomOffset() {
        return viewOffsetHelper != null ? viewOffsetHelper.getTopAndBottomOffset() : 0;
    }

    public int getLeftAndRightOffset() {
        return viewOffsetHelper != null ? viewOffsetHelper.getLeftAndRightOffset() : 0;
    }

    public void setVerticalOffsetEnabled(boolean verticalOffsetEnabled) {
        if (viewOffsetHelper != null) {
            viewOffsetHelper.setVerticalOffsetEnabled(verticalOffsetEnabled);
        }
    }

    public boolean isVerticalOffsetEnabled() {
        return viewOffsetHelper != null && viewOffsetHelper.isVerticalOffsetEnabled();
    }

    public void setHorizontalOffsetEnabled(boolean horizontalOffsetEnabled) {
        if (viewOffsetHelper != null) {
            viewOffsetHelper.setHorizontalOffsetEnabled(horizontalOffsetEnabled);
        }
    }

    public boolean isHorizontalOffsetEnabled() {
        return viewOffsetHelper != null && viewOffsetHelper.isHorizontalOffsetEnabled();
    }
}
