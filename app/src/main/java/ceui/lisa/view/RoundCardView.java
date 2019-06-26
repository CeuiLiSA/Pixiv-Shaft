package ceui.lisa.view;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.CardView;
import android.util.AttributeSet;

import ceui.lisa.utils.Common;

public class RoundCardView extends CardView {

    public RoundCardView(@NonNull Context context) {
        super(context);
    }

    public RoundCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RoundCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        Common.showLog("heightMeasureSpec " + heightMeasureSpec);

    }
}
