package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class LinearItemWithHeadDecoration extends RecyclerView.ItemDecoration {
    private int space;

    public LinearItemWithHeadDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {
        outRect.left = space;
        outRect.right = space;
        outRect.bottom = space;

        int position = parent.getChildAdapterPosition(view);

        if (position == 0) {
            outRect.top = 0;
            outRect.right = 0;
            outRect.left = 0;
        }
    }
}
