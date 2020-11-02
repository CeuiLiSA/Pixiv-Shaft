package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;

import ceui.lisa.R;
import ceui.lisa.utils.Common;

public class RadiusCard extends CardView {

    public RadiusCard(@NonNull Context context) {
        super(context);
    }

    public RadiusCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RadiusCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setRadius(getHeight() / 2.0f);
    }
}
