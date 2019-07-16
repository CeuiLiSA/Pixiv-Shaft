package ceui.lisa.activities;

import android.graphics.Color;
import android.util.Log;
import android.view.View;

import com.mxn.soul.flowingdrawer_core.ElasticDrawer;
import com.mxn.soul.flowingdrawer_core.FlowingDrawer;

import ceui.lisa.R;

public class TActivity extends BaseActivity {

    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_t;
    }

    @Override
    protected void initView() {
    }

    @Override
    protected void initData() {

    }
}
