package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringChain;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.LoginActivity;
import ceui.lisa.activities.LoginAlphaActivity;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.utils.Common;

public class FragmentSettings extends BaseFragment {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_settings;
    }

    @Override
    View initView(View v) {
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> getActivity().finish());
        LinearLayout linearLayout = v.findViewById(R.id.parent_linear);
        animate(linearLayout);

        RelativeLayout loginOut = v.findViewById(R.id.login_out);
        loginOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, LoginAlphaActivity.class);
                startActivity(intent);
                getActivity().finish();
            }
        });

        RelativeLayout userManage = v.findViewById(R.id.user_manage);
        userManage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "账号管理");
                startActivity(intent);
                getActivity().finish();
            }
        });
        return v;
    }

    @Override
    void initData() {

    }

    private void animate(LinearLayout linearLayout){
        SpringChain springChain = SpringChain.create(40,8,60,10);

        int childCount = linearLayout.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View view = linearLayout.getChildAt(i);

            final int position = i;
            springChain.addSpring(new SimpleSpringListener() {
                @Override
                public void onSpringUpdate(Spring spring) {
                    view.setTranslationX((float) spring.getCurrentValue());
                    //view.setAlpha((float) ((400 - spring.getCurrentValue()) / 400 ) );
                    if(position == 0){
                        Common.showLog(className + (float) spring.getCurrentValue());
                    }
                }
            });
        }

        List<Spring> springs = springChain.getAllSprings();
        for (int i = 0; i < springs.size(); i++) {
            springs.get(i).setCurrentValue(400);
        }
        springChain.setControlSpringIndex(0).getControlSpring().setEndValue(0);
    }
}
