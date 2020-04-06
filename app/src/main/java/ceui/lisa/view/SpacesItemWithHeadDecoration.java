package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

public class SpacesItemWithHeadDecoration extends RecyclerView.ItemDecoration {

    private int space;

    public SpacesItemWithHeadDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {

        outRect.bottom = space;

        int position = parent.getChildAdapterPosition(view);


        StaggeredGridLayoutManager.LayoutParams params = (StaggeredGridLayoutManager.LayoutParams) view.getLayoutParams();
        if (params.getSpanIndex() % 2 != 0) {
            //右边
            outRect.left = space / 2;
            outRect.right = space;
        } else {
            //左边
            outRect.left = space;
            outRect.right = space / 2;
        }

        if (position == 0) {
            outRect.top = 0;
            outRect.right = 0;
            outRect.left = 0;
        }
    }
}
