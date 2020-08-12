package ceui.lisa.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;


public abstract class BaseFragment<Layout extends ViewDataBinding> extends Fragment {

    protected Context mContext;
    protected FragmentActivity mActivity;
    protected int mLayoutID = -1;
    protected String className = getClass().getSimpleName() + " ";
    protected Layout baseBind;
    protected View parentView;
    protected Handler mainHandler;
    protected boolean isVertical = true;

    public BaseFragment() {
        Common.showLog(className + "new instance");
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler();

        mContext = requireContext();
        mActivity = requireActivity();

        Bundle bundle = getArguments();
        if (bundle != null) {
            initBundle(bundle);
        }

        Intent intent = mActivity.getIntent();
        if (intent != null) {
            Bundle activityBundle = intent.getExtras();
            if (activityBundle != null) {
                initActivityBundle(bundle);
            }
        }


        if (eventBusEnable()) {
            EventBus.getDefault().register(this);
        }

        //获取屏幕方向
        int ori = getResources().getConfiguration().orientation;
        if (ori == Configuration.ORIENTATION_LANDSCAPE) {
            isVertical = false;
        } else if (ori == Configuration.ORIENTATION_PORTRAIT) {
            isVertical = true;
        }
    }

    public void initActivityBundle(Bundle bundle) {

    }

    @Override
    public void onDestroy() {
        if (eventBusEnable()) {
            EventBus.getDefault().unregister(this);
        }
        mainHandler = null;
        super.onDestroy();
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (parentView == null) {
            initLayout();
            baseBind = DataBindingUtil.inflate(inflater, mLayoutID, container, false);
            if (baseBind != null) {
                parentView = baseBind.getRoot();
            } else {
                parentView = inflater.inflate(mLayoutID, container, false);
            }
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
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (isVertical) {
            vertical();
        } else {
            horizon();
        }
    }

    public void initBundle(Bundle bundle) {

    }

    public void initView(View view) {

    }

    public abstract void initLayout();

    void initData() {

    }

    /**
     * 是否自动注册EventBus，懒得去每个子类里面写注册了
     *
     * @return default false
     */
    public boolean eventBusEnable() {
        return false;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
        if (className.contains(event.getReceiver())) {
            handleEvent(event);
        }
    }

    public void handleEvent(Channel channel) {
    }

    public void horizon() {

    }

    public void vertical() {

    }
}
