package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class LinearItemSearchDecoration extends RecyclerView.ItemDecoration {
    private final int top;
    private final int right;

    public LinearItemSearchDecoration(int top, int right) {
        this.top = top;
        this.right = right;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {
        outRect.left = right;
        outRect.right = right;
        outRect.bottom = top;

        if (parent.getChildPosition(view) == 0) {
            outRect.top = top;
        }
    }
}
