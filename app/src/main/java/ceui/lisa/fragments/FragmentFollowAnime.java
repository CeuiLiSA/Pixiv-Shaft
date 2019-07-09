package ceui.lisa.fragments;

import android.view.View;
import android.widget.ImageView;

import ceui.lisa.R;

public class FragmentFollowAnime extends BaseFragment {

    @Override
    void initLayout() {
        mLayoutID = R.layout.frgament_follow_anime;
    }

    @Override
    View initView(View v) {

        ImageView parent = v.findViewById(R.id.parent);
        ImageView child = v.findViewById(R.id.child);

        return v;
    }

    @Override
    void initData() {

    }
}
