package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.fragment.app.FragmentTransaction;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;

public class FragmentRight extends BaseFragment {

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
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐用户");
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

        if (isVisibleToUser && !isLoad && isAdded()) {
            FragmentRecmdUserHorizontal recmdUser = new FragmentRecmdUserHorizontal();
            FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
            transaction.add(R.id.fragment_container, recmdUser).commit();

            FragmentP fragmentFollowIllust = new FragmentP();
            transaction = getChildFragmentManager().beginTransaction();
            transaction.add(R.id.fragment_recy, fragmentFollowIllust).commit();
            isLoad = true;
        }
    }
}
