package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v4.app.FragmentTransaction;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
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

        v.findViewById(R.id.see_more).setOnClickListener(view -> {
            Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
            intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "推荐用户");
            startActivity(intent);
        });

        return v;
    }

    @Override
    void initData() {

    }


    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if(isVisibleToUser && !isLoad && isAdded()) {
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
