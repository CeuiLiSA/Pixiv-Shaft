package ceui.lisa.utils;

import android.graphics.Rect;
import android.support.v7.widget.RecyclerView;
import android.view.View;

public class LinearItemDecorationNoLR extends RecyclerView.ItemDecoration {
    private int space;

    public LinearItemDecorationNoLR(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {

        outRect.bottom = space;

        if (parent.getChildPosition(view) == 0) {
            outRect.top = space;
        }
    }
}
