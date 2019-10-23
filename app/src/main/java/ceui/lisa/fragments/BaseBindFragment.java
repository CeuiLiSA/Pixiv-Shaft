package ceui.lisa.fragments;

import android.content.Context;
import android.os.Bundle;
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

import ceui.lisa.interfaces.Binding;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;


public abstract class BaseBindFragment<T extends ViewDataBinding> extends Fragment implements Binding<T> {

    protected Context mContext;
    protected FragmentActivity mActivity;
    protected int mLayoutID = -1;
    protected String className = getClass().getSimpleName() + " ";
    protected T baseBind;
    protected View parentView;

    public BaseBindFragment() {
        Common.showLog(className + "new instance");
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = getContext();
        mActivity = getActivity();

        if (eventBusEnable()) {
            EventBus.getDefault().register(this);
        }
    }

    @Override
    public void onDestroy() {
        if (eventBusEnable()) {
            EventBus.getDefault().unregister(this);
        }
        super.onDestroy();
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (parentView == null) {
            initLayout();
            baseBind = getBind(inflater, container);
            parentView = baseBind.getRoot();
            initData();
        } else {
            ViewGroup viewGroup = (ViewGroup) parentView.getParent();
            if (viewGroup != null) {
                viewGroup.removeView(parentView);
            }
        }
        return parentView;
    }

    abstract void initLayout();

    abstract void initData();


    public void getFirstData() {

    }

    public void getNextData() {

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

    @Override
    public T getBind(LayoutInflater inflater, ViewGroup container) {
        return DataBindingUtil.inflate(inflater, mLayoutID, container, false);
    }
}
