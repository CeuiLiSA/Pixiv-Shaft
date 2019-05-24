package ceui.lisa.fragments;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.response.UserModel;

public abstract class BaseFragment extends Fragment {

    protected Context mContext;
    protected Activity mActivity;
    protected int mLayoutID;
    protected View parentView;
    protected UserModel mUserModel;
    protected SpringSystem springSystem = SpringSystem.create();
    protected Spring spring;

    protected String className = this.getClass().getSimpleName() + " ";

    abstract void initLayout();

    abstract View initView(View v);

    abstract void initData();

    public BaseFragment(){
        spring = springSystem.createSpring();
        spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(50,25));
        spring.addListener(new SimpleSpringListener() {

            @Override
            public void onSpringUpdate(Spring spring) {
                // You can observe the updates in the spring
                // state by asking its current value in onSpringUpdate.
                float value = (float) spring.getCurrentValue();

                float scale = 1.25f - value / 4;
                Common.showLog(className + scale);
                parentView.setScaleX(scale);
                parentView.setScaleY(scale);
            }
        });
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getContext();
        mActivity = getActivity();
        mUserModel = Local.getUser();



// Add a listener to observe the motion of the spring.

    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (parentView == null) {
            initLayout();
            parentView = inflater.inflate(mLayoutID, container, false);
            initView(parentView);
            initData();
        } else {
            ViewGroup viewGroup = (ViewGroup) parentView.getParent();
            if (viewGroup != null) {
                viewGroup.removeView(parentView);
            }
        }


        return parentView;
    }



    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if(isVisibleToUser){

            Common.showLog("setUserVisibleHint 被看见了" + className );
        }else {
            Common.showLog("setUserVisibleHint 消失了" + className );
        }
    }


    @Override
    public void onResume() {
        super.onResume();


        spring.setEndValue(1);

    }
}
