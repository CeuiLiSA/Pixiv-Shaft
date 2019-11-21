package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class LinearItemHorizontalDecorationNoTB extends RecyclerView.ItemDecoration {
    private int space;

    public LinearItemHorizontalDecorationNoTB(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {
        outRect.top = 0;
        outRect.right = space;
        outRect.bottom = 0;

        if (parent.getChildPosition(view) == 0) {
            outRect.left = space;
        }
    }
}
