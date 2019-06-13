package ceui.lisa.view;

import android.graphics.Rect;
import android.support.v7.widget.RecyclerView;
import android.view.View;

public class SpacesItemDecoration extends RecyclerView.ItemDecoration {

    private int space;

    public SpacesItemDecoration(int space) {
        this.space=space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        outRect.left=space;
        outRect.right=space;
        outRect.bottom= 2 * space;
        //注释这两行是为了上下间距相同

        int position = parent.getChildAdapterPosition(view);
        if(position == 0 || position == 1){
            outRect.top= 2 * space ;
        }
    }

}
