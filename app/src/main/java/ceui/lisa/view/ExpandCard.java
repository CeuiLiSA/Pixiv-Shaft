package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import ceui.lisa.utils.Common;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class ExpandCard extends CardView {

    private boolean isExpand = true;//默认展开
    private int maxHeight = 0;
    private Context mContext;

    public ExpandCard(@NonNull Context context) {
        super(context);
        mContext = context;
        maxHeight = (mContext.getResources().getDisplayMetrics().heightPixels) * 7 / 10;
    }

    public ExpandCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        maxHeight = (mContext.getResources().getDisplayMetrics().heightPixels) * 7 / 10;
    }

    public ExpandCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        maxHeight = (mContext.getResources().getDisplayMetrics().heightPixels) * 7 / 10;
    }

    public void open() {
        if(isExpand){
            return;
        }

        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.height = WRAP_CONTENT;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof RecyclerView) {
                final RecyclerView recyclerView = ((RecyclerView) getChildAt(i));
                if (recyclerView.getLayoutManager() instanceof ScrollChange) {
                    ((ScrollChange) recyclerView.getLayoutManager()).setScrollEnabled(true);
                }
            }
        }
        setLayoutParams(layoutParams);
        isExpand = true;
    }

    public void close() {
        if(isExpand){
            ViewGroup.LayoutParams layoutParams = getLayoutParams();
            layoutParams.height = maxHeight;
            for (int i = 0; i < getChildCount(); i++) {
                if (getChildAt(i) instanceof RecyclerView) {
                    final RecyclerView recyclerView = ((RecyclerView) getChildAt(i));
                    if (recyclerView.getLayoutManager() instanceof ScrollChange) {
                        ((ScrollChange) recyclerView.getLayoutManager()).setScrollEnabled(false);
                    }
                }
            }
            setLayoutParams(layoutParams);
            isExpand = false;
        }
    }

    public boolean isExpand() {
        return isExpand;
    }

    public void setExpand(boolean pExpand) {
        isExpand = pExpand;
    }
}
