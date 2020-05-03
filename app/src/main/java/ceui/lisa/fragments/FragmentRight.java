package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.FragmentTransaction;

import java.io.Serializable;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentRightBinding;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Params;

public class FragmentRight extends BaseFragment<FragmentRightBinding> {

    private boolean isLoad = false;

    @Override
    public void initLayout() {
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
            transaction.add(R.id.fragment_container, recmdUser, "FragmentRecmdUserHorizontal");

            FragmentEvent fragmentFollowIllust = new FragmentEvent();
            transaction.add(R.id.fragment_recy, fragmentFollowIllust);

            transaction.commitNow();

            isLoad = true;
        }
    }

    @Override
    public boolean eventBusEnable() {
        return true;
    }

    @Override
    public void handleEvent(Channel channel) {
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        FragmentEvent fragmentFollowIllust = new FragmentEvent();
        transaction.add(R.id.fragment_recy, fragmentFollowIllust);
        transaction.commitNowAllowingStateLoss();
    }
}
