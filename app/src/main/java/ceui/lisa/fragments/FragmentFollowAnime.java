package ceui.lisa.fragments;

import android.support.v4.widget.ViewDragHelper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import ceui.lisa.R;
import ceui.lisa.view.AnimateImageView;
import ceui.lisa.view.DragView;
import ceui.lisa.view.ViewTrackController;

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
