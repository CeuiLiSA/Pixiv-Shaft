package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentLockBinding;

public class FragmentLock extends BaseFragment<FragmentLockBinding> {

    public static FragmentLock newInstance() {
        Bundle args = new Bundle();
        FragmentLock fragment = new FragmentLock();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_lock;
    }
}
