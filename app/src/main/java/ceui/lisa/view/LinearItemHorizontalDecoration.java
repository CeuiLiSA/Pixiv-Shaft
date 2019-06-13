package ceui.lisa.view;

import android.graphics.Rect;
import android.support.v7.widget.RecyclerView;
import android.view.View;

public class LinearItemHorizontalDecoration extends RecyclerView.ItemDecoration {
    private int space;

    public LinearItemHorizontalDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {
        outRect.top = space;
        outRect.right = space;
        outRect.bottom = space;

        if (parent.getChildPosition(view) == 0) {
            outRect.left = space;
        }
    }
}
