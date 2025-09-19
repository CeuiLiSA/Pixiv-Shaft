package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class LinearItemOnlyTopDecoration extends RecyclerView.ItemDecoration {
    private final int top;

    public LinearItemOnlyTopDecoration(int top) {
        this.top = top;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {
        outRect.bottom = top;

        if (parent.getChildPosition(view) == 0) {
            outRect.top = top;
        }
    }
}
