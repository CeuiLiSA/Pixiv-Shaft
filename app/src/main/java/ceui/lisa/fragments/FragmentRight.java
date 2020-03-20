package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.FragmentTransaction;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentRightBinding;

public class FragmentRight extends BaseBindFragment<FragmentRightBinding> {

    private boolean isLoad = false;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_right;
    }

    @Override
    public void initView(View view) {
        ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        baseBind.head.setLayoutParams(headParams);

        baseBind.seeMore.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐用户");
            startActivity(intent);
        });
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser && !isLoad && isAdded()) {
            FragmentTransaction transaction = getChildFragmentManager().beginTransaction();

            FragmentRecmdUserHorizontal recmdUser = new FragmentRecmdUserHorizontal();
            transaction.add(R.id.fragment_container, recmdUser);

            FragmentP fragmentFollowIllust = new FragmentP();
            transaction.add(R.id.fragment_recy, fragmentFollowIllust);

            transaction.commitNow();

            isLoad = true;
        }
    }
}
