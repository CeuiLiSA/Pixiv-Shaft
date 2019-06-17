package ceui.lisa.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;


/**
 * Origin:https://github.com/RikkaW/SearchByImage.git
 *
 * Created by Rikka on 2016/1/7.
 */
public class ContextMenuTitleView extends ScrollView {
    private static final int MAX_HEIGHT_DP = 70;
    private static final int PADDING_DP = 16;

    public ContextMenuTitleView(Context context) {
        super(context);
    }

    public ContextMenuTitleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ContextMenuTitleView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ContextMenuTitleView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ContextMenuTitleView(Context context, String title) {
        super(context);

        int padding = dpToPx(PADDING_DP);
        setPadding(padding, padding, padding, 0);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setTextColor(Color.BLACK);
        addView(titleView);
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(dpToPx(MAX_HEIGHT_DP), MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public static int dpToPx(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }
}
