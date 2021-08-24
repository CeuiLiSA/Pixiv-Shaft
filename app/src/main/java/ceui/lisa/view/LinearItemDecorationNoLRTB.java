package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class LinearItemDecorationNoLRTB extends RecyclerView.ItemDecoration {
    private final int space;

    public LinearItemDecorationNoLRTB(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {


        if (parent.getChildPosition(view) != 0) {
            outRect.top = space;
        }
    }
}
