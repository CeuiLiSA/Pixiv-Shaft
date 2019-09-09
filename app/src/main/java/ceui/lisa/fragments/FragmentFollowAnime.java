package ceui.lisa.fragments;

import android.view.View;

import ceui.lisa.R;
import ceui.lisa.view.DragView;

public class FragmentFollowAnime extends BaseFragment {


    @Override
    void initLayout() {
        mLayoutID = R.layout.frgament_follow_anime;
    }

    @Override
    View initView(View v) {
        DragView dragView = v.findViewById(R.id.parent_view);
        return dragView;
    }


    @Override
    void initData() {

    }
}
