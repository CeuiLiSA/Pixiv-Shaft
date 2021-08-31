package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentTestBinding;

public class TestFragment extends BaseFragment<FragmentTestBinding>{

    public static TestFragment newInstance(int index) {
        Bundle args = new Bundle();
        args.putInt("index", index);
        TestFragment fragment = new TestFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected void initView() {

    }

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_test;
    }
}
