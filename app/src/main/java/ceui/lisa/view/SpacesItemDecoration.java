package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

public class SpacesItemDecoration extends RecyclerView.ItemDecoration {

    private final int space;

    public SpacesItemDecoration(int space) {
        this.space = space;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        outRect.bottom = space;
        applyColumnOffsets(outRect, view, parent.getChildAdapterPosition(view), parent, space);
    }

    /**
     * 按瀑布流**当前**列数给左右与首行顶部间距：两侧边缘 space、中缝两边各 space/2。
     * 列数读 LayoutManager 而不是「每行几列」设置——列数会随列表宽度自适应（#1087）。
     */
    static void applyColumnOffsets(Rect outRect, View view, int position, RecyclerView parent, int space) {
        if (!(parent.getLayoutManager() instanceof StaggeredGridLayoutManager)) return;
        int spanCount = ((StaggeredGridLayoutManager) parent.getLayoutManager()).getSpanCount();
        StaggeredGridLayoutManager.LayoutParams params =
                (StaggeredGridLayoutManager.LayoutParams) view.getLayoutParams();
        if (position >= 0 && position < spanCount) {
            outRect.top = space;
        }
        int spanIndex = params.getSpanIndex();
        outRect.left = spanIndex == 0 ? space : space / 2;
        outRect.right = spanIndex == spanCount - 1 ? space : space / 2;
    }
}
