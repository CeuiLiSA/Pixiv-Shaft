package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class TagItemDecoration2 extends RecyclerView.ItemDecoration {

    private final int spacing;

    public TagItemDecoration2(int spacing) {
        this.spacing = spacing;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view); // item position

        if (position == 0) {
            outRect.left = spacing;
            outRect.top = spacing;
            outRect.bottom = spacing;
            outRect.right = spacing / 2;
        } else if(position == 1){
            outRect.right = spacing;
            outRect.top = spacing;
            outRect.bottom = spacing;
            outRect.left = spacing / 2;
        } else {
            if (position % 2 == 0) {
                outRect.left = spacing;
                outRect.bottom = spacing;
                outRect.right = spacing / 2;
            } else {
                outRect.left = spacing / 2;
                outRect.bottom = spacing;
                outRect.right = spacing;
            }
        }
    }
}
