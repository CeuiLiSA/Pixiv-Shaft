package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.effective.android.panel.view.panel.PanelView;

import ceui.lisa.R;
import ceui.lisa.adapters.EmojiAdapter;
import ceui.lisa.utils.Emoji;

public class EmojiLinear extends LinearLayout {

    private EmojiAdapter mAdapter;
    private RecyclerView mRecyclerView;

    public EmojiLinear(Context context) {
        super(context);
        initView();
    }

    public EmojiLinear(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public EmojiLinear(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView();
    }

    public EmojiLinear(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initView();
    }

    private void initView() {

    }
}
