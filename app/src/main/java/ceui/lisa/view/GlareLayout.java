package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.databinding.DataBindingUtil;

import ceui.lisa.R;
import ceui.lisa.databinding.GlareLayoutBinding;

public class GlareLayout extends RelativeLayout {

    private Context mContext;
    private GlareLayoutBinding baseBind;
    private int currentState = 0; //0全部，1公开，2私人
    private OnCheckChangeListener mListener;

    public GlareLayout(Context context) {
        super(context);
        init();
    }

    public GlareLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GlareLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public GlareLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        mContext = getContext();
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        baseBind = DataBindingUtil.inflate(inflater, R.layout.glare_layout, this, true);
        currentState = 0;
        baseBind.left.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentState != 0) {
                    currentState = 0;
                    check(0);
                    if (mListener != null) {
                        mListener.onSelect(0, v);
                    }
                } else {
                    if (mListener != null) {
                        mListener.onReselect(0, v);
                    }
                }
            }
        });
        baseBind.center.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentState != 1) {
                    currentState = 1;
                    check(1);
                    if (mListener != null) {
                        mListener.onSelect(1, v);
                    }
                } else {
                    if (mListener != null) {
                        mListener.onReselect(1, v);
                    }
                }
            }
        });

        baseBind.right.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentState != 2) {
                    currentState = 2;
                    check(2);
                    if (mListener != null) {
                        mListener.onSelect(2, v);
                    }
                } else {
                    if (mListener != null) {
                        mListener.onReselect(2, v);
                    }
                }
            }
        });
    }

    private void check(int index) {
        if (index == 0) {
            baseBind.left.setTextColor(R.attr.colorPrimary);
            baseBind.left.setBackgroundResource(R.drawable.glare_selected);
            unCheck(1);
            unCheck(2);
        } else if (index == 1) {
            baseBind.center.setTextColor(R.attr.colorPrimary);
            baseBind.center.setBackgroundResource(R.drawable.glare_selected);
            unCheck(0);
            unCheck(2);
        } else if (index == 2) {
            baseBind.right.setTextColor(R.attr.colorPrimary);
            baseBind.right.setBackgroundResource(R.drawable.glare_selected);
            unCheck(0);
            unCheck(1);
        }
    }

    private void unCheck(int index) {
        if (index == 0) {
            baseBind.left.setTextColor(getResources().getColor(R.color.glare_unselected_text));
            baseBind.left.setBackground(null);
        } else if (index == 1) {
            baseBind.center.setTextColor(getResources().getColor(R.color.glare_unselected_text));
            baseBind.center.setBackground(null);
        } else if (index == 2) {
            baseBind.right.setTextColor(getResources().getColor(R.color.glare_unselected_text));
            baseBind.right.setBackground(null);
        }
    }

    public OnCheckChangeListener getListener() {
        return mListener;
    }

    public void setListener(OnCheckChangeListener listener) {
        mListener = listener;
    }
}
