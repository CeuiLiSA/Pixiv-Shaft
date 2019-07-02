package ceui.lisa.fragments;

import android.content.pm.ActivityInfo;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import ceui.lisa.R;

/**
 * 地铁表白器
 */
public class FragmentMetro extends BaseFragment {



    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_metro;
    }

    @Override
    View initView(View v) {

        return v;
    }

    @Override
    void initData() {

    }

    @Override
    public void onResume() {
        super.onResume();

        if(mActivity.getRequestedOrientation()!= ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE){
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
    }
}
