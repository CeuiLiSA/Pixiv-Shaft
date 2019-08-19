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

import ceui.lisa.interfaces.Binding;
import ceui.lisa.utils.Common;


public abstract class BaseBindFragment<T extends ViewDataBinding> extends Fragment implements Binding<T> {

    protected Context mContext;
    protected FragmentActivity mActivity;
    protected int mLayoutID = -1;
    protected String className = getClass().getSimpleName() + " ";
    protected T baseBind;

    public BaseBindFragment() {
        Common.showLog(className + "new instance");
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = getContext();
        mActivity = getActivity();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        initLayout();
        baseBind = getBind(inflater, container);
        return baseBind.getRoot();
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initData();
    }

    abstract void initLayout();

    abstract void initData();


    public void getFirstData() {

    }

    public void getNextData() {

    }

    @Override
    public T getBind(LayoutInflater inflater, ViewGroup container) {
        return DataBindingUtil.inflate(inflater, mLayoutID,
                container, false);
    }
}
