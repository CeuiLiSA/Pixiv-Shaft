package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import ceui.lisa.R;
import ceui.lisa.core.GlideApp;
import ceui.lisa.databinding.FragmentTestBinding;
import me.jessyan.progressmanager.ProgressListener;
import me.jessyan.progressmanager.ProgressManager;
import me.jessyan.progressmanager.body.ProgressInfo;

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
