package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v4.app.FragmentTransaction;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import ceui.lisa.R;
import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;

public class FragmentCenter extends BaseFragment {

    private boolean isLoad = false;

    public FragmentCenter() {
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_center;
    }

    @Override
    View initView(View v) {
        ImageView head = v.findViewById(R.id.head);
        ViewGroup.LayoutParams headParams = head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        head.setLayoutParams(headParams);

        TextView textView = v.findViewById(R.id.see_more);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, RankActivity.class);
                startActivity(intent);
            }
        });

        FragmentRankHorizontal fragmentRankHorizontal = new FragmentRankHorizontal();
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.add(R.id.fragment_container, fragmentRankHorizontal).commit();
        return v;
    }

    @Override
    void initData() {

    }




    @Override
    public void onStop() {
        super.onStop();

    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if(isVisibleToUser && !isLoad) {

            FragmentPivisionHorizontal fragmentPivision = new FragmentPivisionHorizontal();
            FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
            transaction.add(R.id.fragment_pivision, fragmentPivision).commit();
            isLoad = true;
            Common.showLog("setUserVisibleHint 被看见了 强行加载");
        }else {
            Common.showLog("setUserVisibleHint 被看见了 加载过了");
        }
    }
}
