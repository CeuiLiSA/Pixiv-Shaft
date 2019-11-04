package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class DownloadItemDecoration extends RecyclerView.ItemDecoration {

    private int spanCount;
    private int spacing;

    public DownloadItemDecoration(int spanCount, int spacing) {
        this.spanCount = spanCount;
        this.spacing = spacing;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view); // item position
        int column = position % spanCount; // item column


        if (column == 0 || column == 1) {
            outRect.top = 0;
            outRect.left = 0;
            outRect.right = spacing;
            outRect.bottom = spacing;
        }

        if (column == 3) {
            outRect.top = 0;
            outRect.left = 0;
            outRect.right = 0;
            outRect.bottom = spacing;
        }
    }
}
