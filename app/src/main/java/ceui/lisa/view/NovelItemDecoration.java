package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class NovelItemDecoration extends RecyclerView.ItemDecoration {
    private final int space;

    public NovelItemDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {
        outRect.left = space;
        outRect.right = space;
        outRect.bottom = space * 2;

        if (parent.getChildPosition(view) == 0) {
            outRect.top = space * 2;
        }
    }
}
