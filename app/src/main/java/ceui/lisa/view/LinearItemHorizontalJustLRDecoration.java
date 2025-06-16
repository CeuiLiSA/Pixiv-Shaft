package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class LinearItemHorizontalJustLRDecoration extends RecyclerView.ItemDecoration {
    private final int space;

    public LinearItemHorizontalJustLRDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {
        if (parent.getChildPosition(view) != 0) {
            outRect.left = space;
        }
    }
}
