package ceui.lisa.helper;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import java.lang.reflect.Field;

import ceui.pixiv.ui.common.AdaptiveStaggerColumns;
import ceui.pixiv.ui.common.TabletLayout;

public class StaggeredManager extends StaggeredGridLayoutManager {

    /** &gt; 0 时列数随列表宽度自适应，以它为手机上的列数，见 {@link #adaptive}；0 = 固定列数。 */
    private int adaptiveBaseColumns = 0;
    private float density = 1f;
    @Nullable private RecyclerView attachedView;

    /**
     * 上一趟布局结束时一个 child 都没留下（各列的行缓存可能已经过期）：下一次测量时先重建列状态。
     * 置位见 {@link #onLayoutChildren}，消费见 {@link #onMeasure}。
     */
    private boolean spanLinesStale;

    /** 被推迟到布局之后的滚动目标：见 {@link #deferAnchorBreakingScroll()}。NO_POSITION = 没有。 */
    private int deferredScrollPosition = RecyclerView.NO_POSITION;

    /** 与 {@link #deferredScrollPosition} 配对的偏移；INVALID_OFFSET = 调用方用的是不带偏移的 scrollToPosition。 */
    private int deferredScrollOffset = INVALID_OFFSET;

    /** SGLM 的 INVALID_OFFSET（包内可见常量），这里写字面值。 */
    private static final int INVALID_OFFSET = Integer.MIN_VALUE;

    /**
     * {@code StaggeredGridLayoutManager.mPendingScrollPosition} / {@code mPendingScrollPositionOffset}。
     * 都是包内可见，跨包只能反射取；release 靠 proguard-rules.pro 的 -keepclassmembers 保住字段名。
     */
    @Nullable
    private static final Field sPendingScrollPositionField = resolveField("mPendingScrollPosition");

    @Nullable
    private static final Field sPendingScrollOffsetField = resolveField("mPendingScrollPositionOffset");

    @Nullable
    private static Field resolveField(String name) {
        try {
            Field field = StaggeredGridLayoutManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * {@code RecyclerView.LayoutParams.mInsetsDirty}。该字段是包内可见的，跨包只能反射取；
     * release 构建靠 proguard-rules.pro 的 -keepclassmembers 保住字段名，取不到时返回 null。
     */
    @Nullable
    private static final Field sInsetsDirtyField = resolveInsetsDirtyField();

    @Nullable
    private static Field resolveInsetsDirtyField() {
        try {
            Field field = RecyclerView.LayoutParams.class.getDeclaredField("mInsetsDirty");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    public StaggeredManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public StaggeredManager(int spanCount, int orientation) {
        super(spanCount, orientation);
    }

    /**
     * 竖向瀑布流：平板排版（{@link TabletLayout}）下列数按列表实际宽度计算（{@link AdaptiveStaggerColumns}），
     * 手机上（横竖屏都算）及关掉「平板适配排版」时固定为 baseColumns，行为与改动前一致。
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
        // 只在平板排版下（最小宽度 >= 600dp 且「平板适配排版」开着）按宽度加列；手机横竖屏一律按
        // 「每行几列」设置，与改动前一致。
        // 读的是当前配置而不是建 LayoutManager 时的：首页不随折叠屏开合重建。没 attach 时不动。
        if (adaptiveBaseColumns > 0 && attachedView != null && !attachedView.isComputingLayout()) {
            int columns = adaptiveBaseColumns;
            boolean tablet = TabletLayout.isEnabled(attachedView.getResources().getConfiguration());
            int contentPx = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            if (tablet) {
                if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.UNSPECIFIED || contentPx <= 0) {
                    columns = getSpanCount();
                } else {
                    columns = AdaptiveStaggerColumns.columnsFor(contentPx / density, adaptiveBaseColumns);
                }
            }
            if (columns != getSpanCount()) {
                setSpanCount(columns);
            }
        }
        // SGLM 清各列行缓存（Span.mCachedStart / mCachedEnd）的那段代码带 getChildCount() > 0 守卫，
        // 而「children 被整体摘掉」的那趟布局恰恰不满足它；连里面的 mInvalidateOffsets 分支也在同一个
        // 守卫里，所以「进布局前把锚点改干净」也救不了。残留的行会被 fill() 直接当成这一列的起排 y
        // （Span.getEndLine 不做合理性检查，getNextSpan 又永远把条目放进「行更小」的那一列），
        // 表现就是某一列顶部空出一块，左右不定、高度不定。
        // 清理只能放在测量阶段：setSpanCount 第一句是 assertNotInLayoutOrScroll，布局回调里调不了。
        // 抖一下列数 = 重建 mSpans（唯一会 new Span[] 的公开口），行缓存与 span 分配一起归零。
        if (spanLinesStale && getItemCount() > 0 && attachedView != null && !attachedView.isComputingLayout()) {
            spanLinesStale = false;
            int spanCount = getSpanCount();
            setSpanCount(spanCount + 1);
            setSpanCount(spanCount);
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
        deferAnchorBreakingScroll();
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
        // 这一趟没留下任何 child：下一趟布局会跳过 SGLM 的列行缓存清理（那段守卫看的就是
        // getChildCount()），残留行会被当成起排 y。记下来，下次测量时先重建列状态（见 onMeasure）。
        // 异常被吞掉的那次也走这里：detachAndScrapAttachedViews 已经跑完，childCount 同样是 0。
        // 这里不补 requestLayout：布局回调里调用会被 RecyclerView 吞掉（dispatchLayoutStep1/2 收尾的
        // stopInterceptRequestLayout(false) 把 mLayoutWasDefered 清零），补了也不生效；真生效的话
        // 空列表每趟布局都会再请求一趟，成了死循环。下一趟布局由数据回来时的 notify 触发。
        spanLinesStale = getChildCount() == 0;
        applyDeferredScroll();
    }

    /**
     * 拆掉「pending scroll + 首个 child 已移除」这个组合。
     *
     * SGLM 的 updateAnchorFromPendingData 在「目标可见」分支里拿 getFirstChildPosition() 当锚点，
     * 而它取的是 getChildAt(0) 的 getPosition()。删除之后那一格可能正是已移除的 holder，返回
     * NO_POSITION(-1)；锚点成了 -1，fill() 的 hasMore() 直接 false —— 整列表一个 child 都排不出来。
     * 更糟的是 AnchorInfo.mValid 仍是 true，后续布局不会重算锚点，空白会一直留着。
     *
     * 这里把这次滚动推迟到布局之后：先清掉 pending scroll，本趟就改走「按可见 child 定锚点」的干净
     * 分支（锚点落在当前可见项上，位置不动）；等这趟把失效 holder 回收掉，下一帧再滚。
     */
    private void deferAnchorBreakingScroll() {
        if (sPendingScrollPositionField == null || getChildCount() == 0) {
            return;
        }
        final int pending;
        try {
            pending = sPendingScrollPositionField.getInt(this);
        } catch (IllegalAccessException e) {
            return;
        }
        if (pending == RecyclerView.NO_POSITION) {
            return;
        }
        View first = getChildAt(0);
        if (first == null || getPosition(first) != RecyclerView.NO_POSITION) {
            return;
        }
        int offset = INVALID_OFFSET;
        try {
            if (sPendingScrollOffsetField != null) {
                offset = sPendingScrollOffsetField.getInt(this);
                sPendingScrollOffsetField.setInt(this, INVALID_OFFSET);
            }
            sPendingScrollPositionField.setInt(this, RecyclerView.NO_POSITION);
        } catch (IllegalAccessException e) {
            return;
        }
        deferredScrollPosition = pending;
        deferredScrollOffset = offset;
    }

    /**
     * 补上上一趟推迟掉的滚动。必须排在整趟布局之后：`onLayoutCompleted`（step3）会把
     * `mPendingScrollPosition` 清成 NO_POSITION，在 onLayoutChildren 里直接补会被它清掉。
     */
    private void applyDeferredScroll() {
        final int position = deferredScrollPosition;
        final int offset = deferredScrollOffset;
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        deferredScrollPosition = RecyclerView.NO_POSITION;
        deferredScrollOffset = INVALID_OFFSET;
        if (attachedView == null) {
            return;
        }
        attachedView.post(new Runnable() {
            @Override
            public void run() {
                // 原样补回：调用方用的是 scrollToPositionWithOffset 时偏移也得带上，否则目标项只会被
                // 「滚到刚好可见」（在下方时贴底），而不是贴到调用方要的位置。
                if (offset == INVALID_OFFSET) {
                    scrollToPosition(position);
                } else {
                    scrollToPositionWithOffset(position, offset);
                }
            }
        });
    }

    /**
     * SGLM 给子项测宽前会经这里取 ItemDecoration 间距。此刻 {@code lp.mSpan} 刚被改派到新 Span，
     * 但框架的 {@code mInsetsDirty} 还停在预布局那一轮，于是直接返回旧 Span 的缓存 insets，
     * 跨列项的左右间距就按旧列算，表现为两列之间那格间距被撑宽。先把该子项置脏，
     * 逼 getItemOffsets() 按新 Span 重算——测量与随后的落位读的都是这份新值。
     * 反射取不到字段时静默跳过，行为退回置脏之前。
     */
    @Override
    public void calculateItemDecorationsForChild(@NonNull View child, @NonNull Rect outRect) {
        markInsetsDirty(child);
        super.calculateItemDecorationsForChild(child, outRect);
    }

    private void markInsetsDirty(View child) {
        if (sInsetsDirtyField == null) {
            return;
        }
        ViewGroup.LayoutParams params = child.getLayoutParams();
        if (!(params instanceof RecyclerView.LayoutParams)) {
            return;
        }
        try {
            sInsetsDirtyField.setBoolean(params, true);
        } catch (IllegalAccessException ignored) {
            // 反射不可用时保持框架原行为
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
