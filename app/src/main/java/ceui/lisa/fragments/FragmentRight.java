package ceui.lisa.fragments;

import android.support.v4.app.FragmentTransaction;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;

public class FragmentRight extends BaseFragment{

    private boolean isLoad = false;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_right;
    }

    @Override
    View initView(View v) {

        ImageView head = v.findViewById(R.id.head);
        ViewGroup.LayoutParams headParams = head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        head.setLayoutParams(headParams);
        return v;
    }

    @Override
    void initData() {

    }


    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if(isVisibleToUser && !isLoad) {
            FragmentRecmdUserHorizontal recmdUser = new FragmentRecmdUserHorizontal();
            FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
            transaction.add(R.id.fragment_container, recmdUser).commit();

            FragmentFollowIllust fragmentFollowIllust = new FragmentFollowIllust();
            transaction = getChildFragmentManager().beginTransaction();
            transaction.add(R.id.fragment_recy, fragmentFollowIllust).commit();
            isLoad = true;
        }
    }
}
