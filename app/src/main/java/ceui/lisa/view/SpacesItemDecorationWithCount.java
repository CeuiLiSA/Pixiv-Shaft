package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

public class SpacesItemDecorationWithCount extends RecyclerView.ItemDecoration {

    private int space;
    private int spanCount;

    public SpacesItemDecorationWithCount(int space, int count) {
        this.space = space;
        this.spanCount = count;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {

        if (spanCount == 2) {
            outRect.bottom = space;

            int position = parent.getChildAdapterPosition(view);
            if (position == 0 || position == 1) {
                outRect.top = space;
            }

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
        } else if (spanCount == 3) {
            outRect.bottom = space;

            int position = parent.getChildAdapterPosition(view);
            if (position == 0 || position == 1 || position == 2) {
                outRect.top = space;
            }

            StaggeredGridLayoutManager.LayoutParams params = (StaggeredGridLayoutManager.LayoutParams) view.getLayoutParams();
            if (params.getSpanIndex() % 3 == 0) {
                //左边
                outRect.left = space;
                outRect.right = space / 2;


            } else if(params.getSpanIndex() % 3 == 1){
                //中间
                outRect.left = space / 2;
                outRect.right = space / 2;
            } else if(params.getSpanIndex() % 3 == 2){
                //右边
                outRect.left = space / 2;
                outRect.right = space;
            }
        }
    }
}
