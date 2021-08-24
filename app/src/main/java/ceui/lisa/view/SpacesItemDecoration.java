package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.lisa.activities.Shaft;

public class SpacesItemDecoration extends RecyclerView.ItemDecoration {

    private final int space;

    public SpacesItemDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {

        outRect.bottom = space;
        int position = parent.getChildAdapterPosition(view);
        StaggeredGridLayoutManager.LayoutParams params = (StaggeredGridLayoutManager.LayoutParams) view.getLayoutParams();


        if (Shaft.sSettings.getLineCount() == 2) {
            if (position == 0 || position == 1) {
                outRect.top = space;
            }

            if (params.getSpanIndex() % 2 != 0) {
                //右边
                outRect.left = space / 2;
                outRect.right = space;
            } else {
                //左边
                outRect.left = space;
                outRect.right = space / 2;
            }
        } else if (Shaft.sSettings.getLineCount() == 3) {
            if (position == 0 || position == 1 || position == 2) {
                outRect.top = space;
            }

            if (params.getSpanIndex() % 3 == 0) {
                //左边
                outRect.left = space;
                outRect.right = space / 2;
            } else if(params.getSpanIndex() % 3 == 1) {
                //中间
                outRect.left = space / 2;
                outRect.right = space / 2;
            }else if(params.getSpanIndex() % 3 == 2) {
                //右边
                outRect.left = space / 2;
                outRect.right = space;
            }
        } else if (Shaft.sSettings.getLineCount() == 4) {
            if (position == 0 || position == 1 || position == 2 || position == 3) {
                outRect.top = space;
            }


            if (params.getSpanIndex() % 4 == 0) {
                //左边
                outRect.left = space;
                outRect.right = space / 2;
            } else if(params.getSpanIndex() % 4 == 1 || params.getSpanIndex() % 4 == 2) {
                //中间
                outRect.left = space / 2;
                outRect.right = space / 2;
            } else if(params.getSpanIndex() % 4 == 3) {
                //右边
                outRect.left = space / 2;
                outRect.right = space;
            }
        }
    }
}
