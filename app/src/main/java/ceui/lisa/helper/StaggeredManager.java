package ceui.lisa.helper;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.pixiv.ui.common.AdaptiveStaggerColumns;

public class StaggeredManager extends StaggeredGridLayoutManager {

    /** &gt; 0 时列数随列表宽度自适应，以它为手机上的列数，见 {@link #adaptive}；0 = 固定列数。 */
    private int adaptiveBaseColumns = 0;
    private float density = 1f;
    @Nullable private RecyclerView attachedView;

    public StaggeredManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public StaggeredManager(int spanCount, int orientation) {
        super(spanCount, orientation);
    }

    /**
     * 竖向瀑布流，列数按列表实际宽度计算（{@link AdaptiveStaggerColumns}），手机上等于 baseColumns。
     * 列表宽度变化（旋转、分栏、导航栏在底栏与侧栏间切换）时在测量阶段改列数，同一帧生效。
     */
    public static StaggeredManager adaptive(Context context, int baseColumns) {
        StaggeredManager manager = new StaggeredManager(Math.max(1, baseColumns), VERTICAL);
        manager.adaptiveBaseColumns = Math.max(1, baseColumns);
        manager.density = context.getResources().getDisplayMetrics().density;
        return manager;
    }

    @Override
    public void onAttachedToWindow(RecyclerView view) {
        super.onAttachedToWindow(view);
        attachedView = view;
    }

    @Override
    public void onDetachedFromWindow(RecyclerView view, RecyclerView.Recycler recycler) {
        super.onDetachedFromWindow(view, recycler);
        attachedView = null;
    }

    @Override
    public void onMeasure(@NonNull RecyclerView.Recycler recycler, @NonNull RecyclerView.State state,
                          int widthSpec, int heightSpec) {
        // 放在 onMeasure：RecyclerView 在这里还没进入 layout，setSpanCount 合法，
        // 随后的 onLayout 直接按新列数排版。挪到 layout 回调里改会晚一帧，而且那次 requestLayout 会丢。
        // 宽度 <= 0 的测量不算数：父布局的预测量（如 ViewPager 被 UNSPECIFIED 量出 0 宽后再用
        // EXACTLY 0 量每一页）如果也改列数，同一帧里就会在 N 列与设置值之间来回切，整列表重排闪烁。
        int contentPx = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        if (adaptiveBaseColumns > 0
                && View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED
                && contentPx > 0
                && (attachedView == null || !attachedView.isComputingLayout())) {
            int columns = AdaptiveStaggerColumns.columnsFor(contentPx / density, adaptiveBaseColumns);
            if (columns != getSpanCount()) {
                setSpanCount(columns);
            }
        }
        super.onMeasure(recycler, state, widthSpec, heightSpec);
    }

    @Override
    public void onScrollStateChanged(int state) {
        try {
            super.onScrollStateChanged(state);
        } catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        // SGLM 的 predictive-animation 预布局(dispatchLayoutStep1)在快速 fling
        // (ViewFlinger.run) + 列表插入新页同帧发生时，框架内部把 pending insert
        // 跟 scrap holder 偏移对不上，抛 "Inconsistency detected. Invalid view
        // holder adapter position" 的 IndexOutOfBoundsException。我们的 notify 计数
        // 是对的，host app 在数据层无法阻止这个 AOSP 内部 bug。在 LayoutManager 这一层
        // 兜住只丢掉这一次坏的布局，fling 不被打断，下一帧按 getItemCount() 干净重建——
        // 比让异常一路冒到 Shaft 主线程兜底(那会整帧 Choreographer 回调全废、fling 卡死)更精准。
        try {
            super.onLayoutChildren(recycler, state);
        } catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        LinearSmoothScroller scroller = new LinearSmoothScroller(recyclerView.getContext()){
            @Override
            protected int getVerticalSnapPreference() {
                return SNAP_TO_START;
            }

            @Override
            protected void onTargetFound(View targetView, RecyclerView.State state, Action action) {
                try {
                    /*
                     * Android 判断一个 View 是否可见 getLocalVisibleRect(rect) 与 getGlobalVisibleRect(rect)
                     *
                     * https://www.bbsmax.com/A/ELPdow2d3a/
                     */

                    if (!targetView.getGlobalVisibleRect(new Rect())) {
                        Rect rect = new Rect();
                        recyclerView.getGlobalVisibleRect(rect);

                        int parentHeight = rect.bottom - rect.top;
                        int childHeight = targetView.getHeight();
                        int offset = (parentHeight - childHeight) / 2;

                        final int dx = calculateDxToMakeVisible(targetView, getHorizontalSnapPreference());
                        final int dy = calculateDyToMakeVisible(targetView, getVerticalSnapPreference()) + offset;
                        final int distance = (int) Math.sqrt(dx * dx + dy * dy);
                        final int time = calculateTimeForDeceleration(distance);
                        if (time > 0) {
                            action.update(-dx, -dy, time, mDecelerateInterpolator);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return 40f / displayMetrics.densityDpi;
            }
        };
        scroller.setTargetPosition(position);
        startSmoothScroll(scroller);
    }

}
