package ceui.lisa.test;

import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;

import ceui.lisa.R;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.interfaces.FullClickListener;
import ceui.lisa.test.BasicActivity;

public class TActivity extends BasicActivity {

    @Override
    public int layout() {
        return R.layout.activity_temp;
    }

    @Override
    public void initView() {
        TextView textView = new TextView(mContext);
        UAdapter uAdapter = new UAdapter(new ArrayList<>(), mContext);
        uAdapter.setFullClickListener(new FullClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {

            }

            @Override
            public void onItemLongClick(View v, int position, int viewType) {

            }
        });
    }

    @Override
    public void initData() {
    }
}
